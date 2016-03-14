package glintlda.mh

import breeze.linalg.Vector
import glint.iterators.RowBlockIterator
import glint.models.client.buffered.BufferedBigMatrix
import glintlda.util.{AggregateBuffer, FastRNG, SimpleLock, time}
import glintlda.{GibbsSample, Solver, LDAModel}

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * A block-coordinate metropolis-hastings based solver
  *
  * @param model The LDA model
  * @param id The identifier
  */
class MHSolver(model: LDAModel, id: Int) extends Solver(model, id) {

  val bufferSize = 100000
  val lock = new SimpleLock(16, logger)

  /**
    * Runs the LDA inference algorithm on given partition of the data
    *
    * @param samples The samples to run the algorithm on
    * @param iteration The iteration number
    */
  override protected def fit(samples: Array[GibbsSample], iteration: Int): Unit = {

    // Create random and sampler
    val random = new FastRNG(model.config.seed + id)
    val sampler = new Sampler(model.config, model.config.mhSteps, random)

    // Pull global topic counts
    val global = Await.result(model.topicCounts.pull((0L until model.config.topics).toArray), 300 seconds)

    // Initialize variables used during iteration of model slices
    var start: Int = 0
    var end: Int = 0
    var rowWait = System.currentTimeMillis()

    // Iterate over blocks of rows of the word topic count matrix
    new RowBlockIterator[Long](model.wordTopicCounts, model.config.blockSize).foreach {
      case rowBlock =>
        logger.info(s"Row block wait time: ${System.currentTimeMillis() - rowWait}ms")

        // Reset flush lock time
        lock.waitTime = 0

        // Perform resampling on just this block of rows from the word topic count matrix
        end += rowBlock.length
        logger.info(s"Resampling features [${start}, ..., ${end})")

        // Compute alias tables
        val aliasTables = time(logger, "Alias time: ") {
          computeAliasTables(rowBlock)
        }

        // Perform resampling
        time(logger, "Resampling time: ") {
          resample(samples, sampler, global, rowBlock, aliasTables, start, end)
        }

        // Log flush lock wait times
        logger.info(s"Flush lock wait time: ${lock.waitTime}ms")

        // Increment start index for next block of rows
        start += rowBlock.length
        rowWait = System.currentTimeMillis()

    }

    // Wait until all changes have succesfully propagated to the parameter server before finishing this iteration
    logger.info(s"Waiting for transfers to finish")
    lock.acquireAll()
    lock.releaseAll()

  }

  /**
    * Runs the LDA inference algorithm on given partition of the data without
    * updating the word-topic counts
    *
    * @param samples The samples to run the algorithm on
    */
  override protected def test(samples: Array[GibbsSample]): Unit = {

    // Create random and sampler
    val random = new FastRNG(model.config.seed + id)
    val sampler = new Sampler(model.config, model.config.mhSteps, random)

    // Sampler should not infer but only test
    sampler.infer = 0

    // Pull global topic counts
    val global = Await.result(model.topicCounts.pull((0L until model.config.topics).toArray), 300 seconds)

    // Initialize variables used during iteration of model slices
    var start: Int = 0
    var end: Int = 0
    var rowWait = System.currentTimeMillis()

    // Iterate over blocks of rows of the word topic count matrix
    new RowBlockIterator[Long](model.wordTopicCounts, model.config.blockSize).foreach {
      case rowBlock =>
        logger.info(s"Row block wait time: ${System.currentTimeMillis() - rowWait}ms")

        // Reset flush lock time
        lock.waitTime = 0

        // Perform resampling on just this block of rows from the word topic count matrix
        end += rowBlock.length
        logger.info(s"Resampling features [${start}, ..., ${end})")

        // Compute alias tables
        val aliasTables = time(logger, "Alias time: ") {
          computeAliasTables(rowBlock)
        }

        // Perform resampling without updating global counts
        time(logger, "Resampling time: ") {
          resample(samples, sampler, global, rowBlock, aliasTables, start, end, false)
        }

        // Increment start index for next block of rows
        start += rowBlock.length
        rowWait = System.currentTimeMillis()
    }

  }

  /**
    * Computes alias tables for given block of features
    *
    * @param block The block of features
    * @return The alias tables
    */
  def computeAliasTables(block: Array[Vector[Long]]): Array[AliasTable] = {
    val aliasTables = new Array[AliasTable](block.length)
    var k = 0
    while (k < block.length) {
      aliasTables(k) = new AliasTable(block(k).map(x => x.toDouble + model.config.β))
      k += 1
    }
    aliasTables
  }

  /**
    * Resamples given samples with
    *
    * @param samples The samples
    * @param sampler The sampler
    * @param global The global topic counts
    * @param block The block of features
    * @param start The index of the first feature
    * @param end The index of the first non-included feature
    * @param shouldUpdateModel A boolean indicating whether the resampling should update the model (default: true)
    */
  def resample(samples: Array[GibbsSample],
               sampler: Sampler,
               global: Array[Long],
               block: Array[Vector[Long]],
               aliasTables: Array[AliasTable],
               start: Int,
               end: Int,
               shouldUpdateModel: Boolean = true) = {

    // Create buffer
    val aggregateBuffer = new AggregateBuffer(model.config.powerlawCutoff, model.config)
    val bufferGlobal = new Array[Long](model.config.topics)
    val buffer = new BufferedBigMatrix[Long](model.wordTopicCounts, bufferSize)

    // Store global counts in sampler
    sampler.globalCounts = global

    // Iterate over documents, resampling each one
    var i = 0
    while (i < samples.length) {

      // Get sample and store appropriate counts in the sampler
      val sample = samples(i)
      sampler.documentCounts = sample.denseCounts(model.config.topics)
      sampler.documentSize = sample.features.length
      sampler.documentTopicAssignments = sample.topics

      // Iterate over features
      var j = 0
      while (j < sample.features.length) {

        // If feature is in the current working set of features we perform actual resampling
        val feature = sample.features(j)
        val oldTopic = sample.topics(j)
        if (feature >= start && feature < end) {

          // Resample feature
          sampler.wordCounts = block(feature - start)
          sampler.aliasTable = aliasTables(feature - start)
          val newTopic = sampler.sampleFeature(feature, oldTopic)

          // Topic has changed, update the necessary counts
          if (oldTopic != newTopic) {
            sample.topics(j) = newTopic
            sampler.documentCounts(oldTopic) -= 1
            sampler.documentCounts(newTopic) += 1

            if (shouldUpdateModel) {
              sampler.wordCounts(oldTopic) -= 1
              sampler.wordCounts(newTopic) += 1
              sampler.globalCounts(oldTopic) -= 1
              sampler.globalCounts(newTopic) += 1

              if (feature < aggregateBuffer.cutoff) {
                aggregateBuffer.add(feature, newTopic, 1)
                aggregateBuffer.add(feature, oldTopic, -1)
              } else {
                // Add to buffer and flush if necessary
                buffer.pushToBuffer(feature, oldTopic, -1)
                flushBufferIfFull(buffer, lock)
                buffer.pushToBuffer(feature, newTopic, 1)
                flushBufferIfFull(buffer, lock)
              }

              bufferGlobal(oldTopic) -= 1
              bufferGlobal(newTopic) += 1
            }

          }
        }

        j += 1
      }
      i += 1
    }

    // Flush powerlaw buffer
    lock.acquire()
    aggregateBuffer.flush(model.wordTopicCounts).onComplete(_ => lock.release())

    // Flush buffer to push changes to word topic counts
    flushBuffer(buffer, lock)

    // Flush global topic counts
    lock.acquire()
    val flushGlobal = model.topicCounts.push((0L until model.config.topics).toArray, bufferGlobal)
    flushGlobal.onComplete(_ => lock.release())
    flushGlobal.onFailure { case ex => println(ex.getMessage + "\n" + ex.getStackTraceString) }

  }

  /**
    * Attempts to flush the buffer if it is full
    *
    * @param buffer The buffer
    * @param lock The semaphore lock to act as a back-pressure
    * @tparam V The type of values stored in the buffer
    */
  @inline
  private def flushBufferIfFull[V](buffer: BufferedBigMatrix[V], lock: SimpleLock): Unit = {
    if (buffer.isFull) {
      flushBuffer(buffer, lock)
    }
  }

  /**
    * Flushes the buffer
    *
    * @param buffer The buffer
    * @param lock The semaphore lock to act as back-pressure
    * @tparam V The type of values stored in the buffer
    */
  @inline
  private def flushBuffer[V](buffer: BufferedBigMatrix[V], lock: SimpleLock): Unit = {
    lock.acquire()
    val flush = buffer.flush()
    flush.onComplete(_ => lock.release())
    flush.onFailure { case ex => logger.error(ex.getMessage + "\n" + ex.getStackTraceString) }
  }

}
