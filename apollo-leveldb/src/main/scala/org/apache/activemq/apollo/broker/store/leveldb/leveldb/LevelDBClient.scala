/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.apollo.broker.store.leveldb

import dto.LevelDBStoreDTO
import java.{lang=>jl}
import java.{util=>ju}

import org.fusesource.hawtbuf.proto.PBMessageFactory
import org.apache.activemq.apollo.broker.store.PBSupport._

import org.apache.activemq.apollo.broker.store._
import java.io._
import java.util.concurrent.TimeUnit
import org.apache.activemq.apollo.util._
import collection.mutable.{HashMap, ListBuffer}
import java.util.concurrent.locks.ReentrantReadWriteLock
import org.fusesource.hawtdispatch._
import org.apache.activemq.apollo.util.{TreeMap=>ApolloTreeMap}
import collection.immutable.TreeMap
import org.iq80.leveldb._
import org.fusesource.leveldbjni.internal.Util
import org.fusesource.hawtbuf.{Buffer, AbstractVarIntSupport}
import java.util.concurrent.atomic.{AtomicReference, AtomicLong}
import org.apache.activemq.apollo.broker.store.MapEntryPB.Bean
import org.apache.activemq.apollo.broker.store.leveldb.HelperTrait._
import org.apache.activemq.apollo.broker.Broker

/**
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
object LevelDBClient extends Log {

  final val message_prefix = 'm'.toByte
  final val queue_prefix = 'q'.toByte
  final val queue_entry_prefix = 'e'.toByte
  final val map_prefix = 'p'.toByte

  final val message_prefix_array = Array(message_prefix)
  final val queue_prefix_array = Array(queue_prefix)
  final val map_prefix_array = Array(map_prefix)
  final val queue_entry_prefix_array = Array(queue_entry_prefix)

  final val dirty_index_key = bytes(":dirty")
  final val TRUE = bytes("true")
  final val FALSE = bytes("false")

  final val LOG_ADD_QUEUE           = 1.toByte
  final val LOG_REMOVE_QUEUE        = 2.toByte
  final val LOG_ADD_MESSAGE         = 3.toByte
  final val LOG_REMOVE_MESSAGE      = 4.toByte
  final val LOG_ADD_QUEUE_ENTRY     = 5.toByte
  final val LOG_REMOVE_QUEUE_ENTRY  = 6.toByte
  final val LOG_MAP_ENTRY           = 7.toByte

  final val LOG_SUFFIX  = ".log"
  final val INDEX_SUFFIX  = ".index"

  def bytes(value:String) = value.getBytes("UTF-8")

  import FileSupport._
  def create_sequence_file(directory:File, id:Long, suffix:String) = directory / ("%016x%s".format(id, suffix))

  def find_sequence_files(directory:File, suffix:String):TreeMap[Long, File] = {
    TreeMap((directory.list_files.flatMap { f=>
      if( f.getName.endsWith(suffix) ) {
        try {
          val base = f.getName.stripSuffix(suffix)
          val position = java.lang.Long.parseLong(base, 16);
          Some(position -> f)
        } catch {
          case e:NumberFormatException => None
        }
      } else {
        None
      }
    }): _* )
  }

  case class UsageCounter() {
    var count = 0L
    var size = 0L
    def increment(value:Int) = {
      count += 1
      size += value
    }
  }
  
  var link_strategy = 0
  def link(source:File, target:File):Unit = {
    link_strategy match {
      case 0 =>
        try {
          Util.link(source, target)
        } catch {
          case e:IOException => throw e
          case e:Throwable =>
            // Fallback.. to a slower impl..
            link_strategy = 100
          link(source, target)
        }

      case _ =>
        // this strategy is slow but sure to work.
        source.copy_to(target)
    }
  }

}

/**
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class LevelDBClient(store: LevelDBStore) {

  import HelperTrait._
  import LevelDBClient._
  import FileSupport._

  def dispatchQueue = store.dispatch_queue

  /////////////////////////////////////////////////////////////////////
  //
  // Helpers
  //
  /////////////////////////////////////////////////////////////////////

  def config = store.config
  def directory = config.directory

  /////////////////////////////////////////////////////////////////////
  //
  // Public interface used by the LevelDBStore
  //
  /////////////////////////////////////////////////////////////////////

  var sync = false;
  var verify_checksums = false;

  var log:RecordLog = _

  var index:RichDB = _
  var index_options:Options = _

  var last_index_snapshot_pos:Long = _
  val snapshot_rw_lock = new ReentrantReadWriteLock(true)

  var last_gc_ts = 0L
  var last_gc_duration = 0L
  var in_gc = false
  var gc_detected_log_usage = Map[Long, UsageCounter]()
  var factory:DBFactory = _


  def dirty_index_file = directory / ("dirty"+INDEX_SUFFIX)
  def temp_index_file = directory / ("temp"+INDEX_SUFFIX)
  def snapshot_index_file(id:Long) = create_sequence_file(directory,id, INDEX_SUFFIX)

  def create_log: RecordLog = {
    new RecordLog(directory, LOG_SUFFIX)
  }

  def log_size = {
    import OptionSupport._
    config.log_size.getOrElse(1024 * 1024 * 100)
  }

  def start() = {
    import OptionSupport._

    val factory_names = Option(config.index_factory).getOrElse("org.fusesource.leveldbjni.JniDBFactory, org.iq80.leveldb.impl.Iq80DBFactory")
    factory = factory_names.split("""(,|\s)+""").map(_.trim()).flatMap { name=>
      try {
        Some(Broker.class_loader.loadClass(name).newInstance().asInstanceOf[DBFactory])
      } catch {
        case x => None
      }
    }.headOption.getOrElse(throw new Exception("Could not load any of the index factory classes: "+factory_names))

    sync = config.sync.getOrElse(true);
    verify_checksums = config.verify_checksums.getOrElse(false);

    index_options = new Options();
    index_options.createIfMissing(true);

    config.index_max_open_files.foreach( index_options.maxOpenFiles(_) )
    config.index_block_restart_interval.foreach( index_options.blockRestartInterval(_) )
    config.paranoid_checks.foreach( index_options.paranoidChecks(_) )
    config.index_write_buffer_size.foreach( index_options.writeBufferSize(_) )
    config.index_block_size.foreach( index_options.blockSize(_) )
    Option(config.index_compression).foreach(x => index_options.compressionType( x match {
      case "snappy" => CompressionType.SNAPPY
      case "none" => CompressionType.NONE
      case _ => CompressionType.SNAPPY
    }) )

    index_options.cacheSize(config.index_cache_size.getOrElse(1024*1024*256L))
    index_options.logger(new Logger() {
      def log(msg: String) = debug(store.store_kind+": "+msg)
    })

    log = create_log
    log.write_buffer_size = config.log_write_buffer_size.getOrElse(1024*1024*4)
    log.log_size = log_size
    log.on_log_rotate = ()=> {
      // lets queue a request to checkpoint when
      // the logs rotate.. queue it on the GC thread since GC's lock
      // the index for a long time.
      store.gc_executor {
        snapshot_index
      }
    }

    retry {
      log.open
    }

    // Find out what was the last snapshot.
    val snapshots = find_sequence_files(directory, INDEX_SUFFIX)
    var last_snapshot_index = snapshots.lastOption
    last_index_snapshot_pos = last_snapshot_index.map(_._1).getOrElse(0)

    // Only keep the last snapshot..
    snapshots.filterNot(_._1 == last_index_snapshot_pos).foreach( _._2.recursive_delete )
    temp_index_file.recursive_delete

    retry {

      // Delete the dirty indexes
      dirty_index_file.recursive_delete
      dirty_index_file.mkdirs()

      last_snapshot_index.foreach { case (id, file) =>
        // Resume log replay from a snapshot of the index..
        try {
          file.list_files.foreach { file =>
            link(file, dirty_index_file / file.getName)
          }
        } catch {
          case e:Exception =>
            warn(e, "Could not recover snapshot of the index: "+e)
            last_snapshot_index  = None
        }
      }

      index = new RichDB(factory.open(dirty_index_file, index_options));
      try {
        index.put(dirty_index_key, TRUE)
        // Update the index /w what was stored on the logs..
        var pos = last_index_snapshot_pos;

        // Replay the log from the last update position..
        try {
          while (pos < log.appender_limit) {
            log.read(pos).map {
              case (kind, data, len) =>
                kind match {
                  case LOG_ADD_MESSAGE =>
                    val record: MessageRecord = data
                    index.put(encode(message_prefix, record.key), encode(pos))
                  case LOG_ADD_QUEUE_ENTRY =>
                    val record: QueueEntryRecord = data
                    index.put(encode(queue_entry_prefix, record.queue_key, record.entry_seq), data)
                  case LOG_REMOVE_QUEUE_ENTRY =>
                    index.delete(data)
                  case LOG_ADD_QUEUE =>
                    val record: QueueRecord = data
                    index.put(encode(queue_prefix, record.key), data)
                  case LOG_REMOVE_QUEUE =>
                    val ro = new ReadOptions
                    ro.fillCache(false)
                    ro.verifyChecksums(verify_checksums)
                    val queue_key = decode_long(data)
                    index.delete(encode(queue_prefix, queue_key))
                    index.cursor_keys_prefixed(encode(queue_entry_prefix, queue_key), ro) {
                      key =>
                        index.delete(key)
                        true
                    }
                  case LOG_MAP_ENTRY =>
                    val entry = MapEntryPB.FACTORY.parseUnframed(data)
                    if (entry.getValue == null) {
                      index.delete(encode(map_prefix, entry.getKey))
                    } else {
                      index.put(encode(map_prefix, entry.getKey), entry.getValue.toByteArray)
                    }
                  case _ =>
                  // Skip unknown records like the RecordLog headers.
                }
                pos += len
            }
          }
        }
        catch {
          case e:Throwable => e.printStackTrace()
        }


      } catch {
        case e:Throwable =>
          // replay failed.. good thing we are in a retry block...
          index.close
          throw e;
      }
    }
  }

  def stop() = {
    // this blocks until all io completes..
    // Suspend also deletes the index.
    suspend()

    if (log != null) {
      log.close
    }
    copy_dirty_index_to_snapshot
    log = null
  }

  def using_index[T](func: =>T):T = {
    val lock = snapshot_rw_lock.readLock();
    lock.lock()
    try {
      func
    } finally {
      lock.unlock()
    }
  }

  def retry_using_index[T](func: =>T):T = retry(using_index( func ))

  /**
   * TODO: expose this via management APIs, handy if you want to
   * do a file system level snapshot and want the data to be consistent.
   */
  def suspend() = {
    // Make sure we are the only ones accessing the index. since
    // we will be closing it to create a consistent snapshot.
    snapshot_rw_lock.writeLock().lock()

    // Close the index so that it's files are not changed async on us.
    index.put(dirty_index_key, FALSE, new WriteOptions().sync(true))
    index.close
  }

  /**
   * TODO: expose this via management APIs, handy if you want to
   * do a file system level snapshot and want the data to be consistent.
   */
  def resume() = {
    // re=open it..
    retry {
      index = new RichDB(factory.open(dirty_index_file, index_options));
      index.put(dirty_index_key, TRUE)
    }
    snapshot_rw_lock.writeLock().unlock()
  }

  def copy_dirty_index_to_snapshot {
    if( log.appender_limit == last_index_snapshot_pos  ) {
      // no need to snapshot again...
      return
    }

    // Where we start copying files into.  Delete this on
    // restart.
    val tmp_dir = temp_index_file
    tmp_dir.mkdirs()

    try {

      // Hard link all the index files.
      dirty_index_file.list_files.foreach { file =>
        link(file, tmp_dir / file.getName)
      }

      // Rename to signal that the snapshot is complete.
      val new_snapshot_index_pos = log.appender_limit
      tmp_dir.renameTo(snapshot_index_file(new_snapshot_index_pos))
      snapshot_index_file(last_index_snapshot_pos).recursive_delete
      last_index_snapshot_pos = new_snapshot_index_pos

    } catch {
      case e: Exception =>
        // if we could not snapshot for any reason, delete it as we don't
        // want a partial check point..
        warn(e, "Could not snapshot the index: " + e)
        tmp_dir.recursive_delete
    }
  }

  def snapshot_index:Unit = {
    if( log.appender_limit == last_index_snapshot_pos  ) {
      // no need to snapshot again...
      return
    }
    suspend()
    try {
      copy_dirty_index_to_snapshot
    } finally {
      resume()
    }
  }

  def retry[T](func: => T): T = {
    var error:Throwable = null
    var rc:Option[T] = None

    // We will loop until the tx succeeds.  Perhaps it's
    // failing due to a temporary condition like low disk space.
    while(!rc.isDefined) {

      try {
        rc = Some(func)
      } catch {
        case e:Throwable =>
          if( error==null ) {
            warn(e, "DB operation failed. (entering recovery mode)")
          }
          error = e
      }

      if (!rc.isDefined) {
        // We may need to give up if the store is being stopped.
        if ( !store.service_state.is_started ) {
          throw error
        }
        Thread.sleep(1000)
      }
    }

    if( error!=null ) {
      info("DB recovered from failure.")
    }
    rc.get
  }

  def purge() = {
    suspend()
    try{
      log.close
      directory.list_files.foreach(_.recursive_delete)
    } finally {
      retry {
        log.open
      }
      resume()
    }
  }

  def add_queue(record: QueueRecord, callback:Runnable) = {
    retry_using_index {
      log.appender { appender =>
        appender.append(LOG_ADD_QUEUE, record)
        index.put(encode(queue_prefix, record.key), record)
      }
    }
    callback.run
  }

  def remove_queue(queue_key: Long, callback:Runnable) = {
    retry_using_index {
      log.appender { appender =>
        val ro = new ReadOptions
        ro.fillCache(false)
        ro.verifyChecksums(verify_checksums)
        appender.append(LOG_REMOVE_QUEUE, encode(queue_key))
        index.delete(encode(queue_prefix, queue_key))
        index.cursor_keys_prefixed(encode(queue_entry_prefix, queue_key), ro) { key=>
          index.delete(key)
          true
        }
      }
    }
    callback.run
  }

  def store(uows: Seq[LevelDBStore#DelayableUOW], callback:Runnable) {
    retry_using_index {
      log.appender { appender =>

        var sync_needed = false
        index.write() { batch =>
          uows.foreach { uow =>

            for((key,value) <- uow.map_actions) {
              val entry = new MapEntryPB.Bean()
              entry.setKey(key)
              if( value==null ) {
                batch.delete(encode(map_prefix, key))
              } else {
                entry.setValue(value)
                batch.put(encode(map_prefix, key), value.toByteArray)
              }
              appender.append(LOG_MAP_ENTRY, entry.freeze().toUnframedByteArray)
            }

            uow.actions.foreach { case (msg, action) =>
              val message_record = action.message_record
              var pos = 0L
              var pos_buffer:Buffer = null

              if (message_record != null) {
                pos = appender.append(LOG_ADD_MESSAGE, message_record)
                val pos_encoded = encode(pos)
                pos_buffer = new Buffer(pos_encoded)
                if( message_record.locator !=null ) {
                  message_record.locator.set(pos_encoded);
                }
                batch.put(encode(message_prefix, action.message_record.key), pos_encoded)
              }

              action.dequeues.foreach { entry =>
                if( pos_buffer==null && entry.message_locator!=null ) {
                  pos_buffer = entry.message_locator
                }
                val key = encode(queue_entry_prefix, entry.queue_key, entry.entry_seq)
                appender.append(LOG_REMOVE_QUEUE_ENTRY, key)
                batch.delete(key)
              }

              action.enqueues.foreach { entry =>
                entry.message_locator = pos_buffer
                val encoded:Array[Byte] = entry
                appender.append(LOG_ADD_QUEUE_ENTRY, encoded)
                batch.put(encode(queue_entry_prefix, entry.queue_key, entry.entry_seq), encoded)
              }
            }
            if( !uow.complete_listeners.isEmpty ) {
              sync_needed = true
            }
          }
        }
        if( sync_needed && sync ) {
          appender.flush
          appender.sync
        }
      }
    }
    callback.run
  }

  val metric_load_from_index_counter = new TimeCounter
  var metric_load_from_index = metric_load_from_index_counter(false)

  def loadMessages(requests: ListBuffer[(Long, AtomicReference[Array[Byte]], (Option[MessageRecord])=>Unit)]):Unit = {

    val ro = new ReadOptions
    ro.verifyChecksums(verify_checksums)
    ro.fillCache(true)

    val missing = retry_using_index {
      index.snapshot { snapshot =>
        ro.snapshot(snapshot)
        requests.flatMap { x =>
          val (message_key, locator, callback) = x
          val record = metric_load_from_index_counter.time {
            var pos = 0L
            var pos_array:Array[Byte] = null
            if( locator!=null ) {
              pos_array = locator.get()
              if( pos_array!=null ) {
                pos = decode_long(pos_array)
              }
            }
            if( pos == 0L ) {
              index.get(encode(message_prefix, message_key), ro) match {
                case Some(value) =>
                  pos_array = value
                  pos = decode_long(pos_array)
                case None =>
                  pos = 0L
              }
            }
            if (pos == 0L ) {
              None
            } else {
              log.read(pos).map { case (prefix, data, _)=>
                val rc:MessageRecord = data
                rc.locator = new AtomicReference[Array[Byte]](pos_array)
                rc
              }
            }
          }
          if( record.isDefined ) {
            callback(record)
            None
          } else {
            Some(x)
          }
        }
      }
    }

    if (missing.isEmpty)
      return

    // There's a small chance that a message was missing, perhaps we started a read tx, before the
    // write tx completed.  Lets try again..
    retry_using_index {
      index.snapshot { snapshot =>
        ro.snapshot(snapshot)
        missing.foreach { x =>
          val (message_key, locator, callback) = x
          val record = metric_load_from_index_counter.time {
            index.get(encode(message_prefix, message_key), ro).flatMap{ pos_array=>
              val pos = decode_long(pos_array)
              log.read(pos).map { case (prefix, data, _)=>
                val rc:MessageRecord = data
                rc.locator = new AtomicReference[Array[Byte]](pos_array)
                rc
              }
            }
          }
          callback(record)
        }
      }
    }
  }

  def list_queues: Seq[Long] = {
    val rc = ListBuffer[Long]()
    retry_using_index {
      val ro = new ReadOptions
      ro.verifyChecksums(verify_checksums)
      ro.fillCache(false)
      index.cursor_keys_prefixed(queue_prefix_array, ro) { key =>
        rc += decode_long_key(key)._2
        true // to continue cursoring.
      }
    }
    rc
  }

  def get_queue(queue_key: Long): Option[QueueRecord] = {
    retry_using_index {
      val ro = new ReadOptions
      ro.fillCache(false)
      ro.verifyChecksums(verify_checksums)
      index.get(encode(queue_prefix, queue_key), ro).map( x=> decode_queue_record(x)  )
    }
  }

  def listQueueEntryGroups(queue_key: Long, limit: Int) : Seq[QueueEntryRange] = {
    var rc = ListBuffer[QueueEntryRange]()
    val ro = new ReadOptions
    ro.verifyChecksums(verify_checksums)
    ro.fillCache(false)
    retry_using_index {
      index.snapshot { snapshot =>
        ro.snapshot(snapshot)

        var group:QueueEntryRange = null
        index.cursor_prefixed( encode(queue_entry_prefix, queue_key), ro) { (key, value) =>

          val (_,_,current_key) = decode_long_long_key(key)
          if( group == null ) {
            group = new QueueEntryRange
            group.first_entry_seq = current_key
          }

          val entry:QueueEntryRecord = value

          group.last_entry_seq = current_key
          group.count += 1
          group.size += entry.size

          if(group.expiration == 0){
            group.expiration = entry.expiration
          } else {
            if( entry.expiration != 0 ) {
              group.expiration = entry.expiration.min(group.expiration)
            }
          }

          if( group.count == limit) {
            rc += group
            group = null
          }

          true // to continue cursoring.
        }
        if( group!=null ) {
          rc += group
        }
      }
    }
    rc
  }

  def getQueueEntries(queue_key: Long, firstSeq:Long, lastSeq:Long): Seq[QueueEntryRecord] = {
    var rc = ListBuffer[QueueEntryRecord]()
    val ro = new ReadOptions
    ro.verifyChecksums(verify_checksums)
    ro.fillCache(true)
    retry_using_index {
      index.snapshot { snapshot =>
        ro.snapshot(snapshot)
        val start = encode(queue_entry_prefix, queue_key, firstSeq)
        val end = encode(queue_entry_prefix, queue_key, lastSeq+1)
        index.cursor_range( start, end, ro ) { (key, value) =>
          rc += value
          true
        }
      }
    }
    rc
  }

  def getLastMessageKey:Long = {
    retry_using_index {
      index.last_key(message_prefix_array).map(decode_long_key(_)._2).getOrElse(0)
    }
  }

  def get(key: Buffer):Option[Buffer] = {
    retry_using_index {
      index.get(encode(map_prefix, key)).map(new Buffer(_))
    }
  }

  def get_last_queue_key:Long = {
    retry_using_index {
      index.last_key(queue_prefix_array).map(decode_long_key(_)._2).getOrElse(0)
    }
  }

  def gc:Unit = {
    var active_counter = 0
    var delete_counter = 0
    val latency_counter = new TimeCounter

    val ro = new ReadOptions()
    ro.fillCache(false)
    ro.verifyChecksums(verify_checksums)

    //
    // This journal_usage will let us get a picture of which queues are using how much of each
    // log file.  It will help folks figure out why a log file is not getting deleted.
    //
    val journal_usage = new ApolloTreeMap[Long,(RecordLog#LogInfo , UsageCounter)]()
    var append_journal = 0L

    log.log_mutex.synchronized {
      append_journal = log.log_infos.last._1
      log.log_infos.foreach(entry=> journal_usage.put(entry._1, (entry._2, UsageCounter())) )
    }

    def find_journal(pos: Long) = {
      var entry = journal_usage.floorEntry(pos)
      if (entry != null) {
        val (info, usageCounter) = entry.getValue()
        if (pos < info.limit) {
          Some(entry.getKey -> usageCounter)
        } else {
          None
        }
      } else {
        None
      }
    }

    in_gc = true
    val now = System.currentTimeMillis()
    debug(store.store_kind+" gc starting")
    latency_counter.time {

      retry_using_index {
        index.snapshot { snapshot =>
          ro.snapshot(snapshot)

          // Figure out which journal files are still in use by which queues.
          index.cursor_prefixed(queue_entry_prefix_array, ro) { (_,value) =>
            val entry_record:QueueEntryRecord = value
            val pos = if(entry_record.message_locator!=null) {
              decode_long(entry_record.message_locator.toByteArray)
            } else {
              index.get(encode(message_prefix, entry_record.message_key)).map(decode_long(_)).getOrElse(0L)
            }

            find_journal(pos) match {
              case Some((key,usageCounter)) =>
                usageCounter.increment(entry_record.size)
              case None =>
            }

            // only continue while the service is still running..
            store.service_state.is_started
          }

          if (store.service_state.is_started) {

            gc_detected_log_usage = Map((collection.JavaConversions.asScalaSet(journal_usage.entrySet()).map { x=>
              x.getKey -> x.getValue._2
            }).toSeq : _ * )

            // Take empty journals out of the map..
            val empty_journals = ListBuffer[Long]()

            val i = journal_usage.entrySet().iterator();
            while( i.hasNext ) {
              val (info, usageCounter) = i.next().getValue
              if( usageCounter.count==0 && info.position < append_journal) {
                empty_journals += info.position
                i.remove()
              }
            }

            index.cursor_prefixed(message_prefix_array) { (key,value) =>
              val pos = decode_long(value)

              if ( !find_journal(pos).isDefined ) {
                // Delete it.
                index.delete(key)
                delete_counter += 1
              } else {
                active_counter += 1
              }
              // only continue while the service is still running..
              store.service_state.is_started
            }

            if (store.service_state.is_started) {
              // We don't want to delete any journals that the index has not snapshot'ed or
              // the the
              val delete_limit = find_journal(last_index_snapshot_pos).map(_._1).
                    getOrElse(last_index_snapshot_pos).min(log.appender_start)

              empty_journals.foreach { id =>
                if ( id < delete_limit ) {
                  log.delete(id)
                }
              }
            }
          }
        }


      }
    }
    last_gc_ts=now
    last_gc_duration = latency_counter.total(TimeUnit.MILLISECONDS)
    in_gc = false
    debug(store.store_kind+" gc ended")
  }


  def export_pb(streams:StreamManager[OutputStream]):Result[Zilch,String] = {
    try {
      retry_using_index {
        index.snapshot { snapshot=>
          val ro = new ReadOptions
          ro.snapshot(snapshot)
          ro.verifyChecksums(verify_checksums)
          ro.fillCache(false)

          def write_framed(stream:OutputStream, value:Array[Byte]) = {
            val helper = new AbstractVarIntSupport {
              def readByte: Byte = throw new UnsupportedOperationException
              def writeByte(value: Int) = stream.write(value)
            }
            helper.writeVarInt(value.length)
            stream.write(value);
            true
          }

          streams.using_map_stream { stream=>
            index.cursor_prefixed(map_prefix_array, ro) { (key, value) =>
              val key_buffer = new Buffer(key)
              key_buffer.moveHead(1)
              val record = new MapEntryPB.Bean
              record.setKey(key_buffer)
              record.setValue(new Buffer(value))
              record.freeze().writeFramed(stream)
              true
            }
          }

          streams.using_queue_stream { stream =>
            index.cursor_prefixed(queue_prefix_array, ro) { (_, value) =>
              write_framed(stream, value)
            }
          }

          streams.using_message_stream { stream=>
            index.cursor_prefixed(message_prefix_array, ro) { (_, value) =>
              write_framed(stream, value)
            }
          }

          streams.using_queue_entry_stream { stream=>
            index.cursor_prefixed(queue_entry_prefix_array, ro) { (_, value) =>
              write_framed(stream, value)
            }
          }

        }
      }
      Success(Zilch)
    } catch {
      case x:Exception=>
        Failure(x.getMessage)
    }
  }

  def import_pb(streams:StreamManager[InputStream]):Result[Zilch,String] = {
    try {
      purge

      retry_using_index {
        def foreach[Buffer] (stream:InputStream, fact:PBMessageFactory[_,_])(func: (Buffer)=>Unit):Unit = {
          var done = false
          do {
            try {
              func(fact.parseFramed(stream).asInstanceOf[Buffer])
            } catch {
              case x:EOFException =>
                done = true
            }
          } while( !done )
        }

        log.appender { appender =>
          streams.using_map_stream { stream=>
            foreach[MapEntryPB.Buffer](stream, MapEntryPB.FACTORY) { pb =>
              index.put(encode(map_prefix, pb.getKey), pb.getValue.toByteArray)
            }
          }

          streams.using_queue_stream { stream=>
            foreach[QueuePB.Buffer](stream, QueuePB.FACTORY) { record=>
              index.put(encode(queue_prefix, record.key), record.toUnframedByteArray)
            }
          }

          streams.using_message_stream { stream=>
            foreach[MessagePB.Buffer](stream, MessagePB.FACTORY) { record=>
              val pos = appender.append(LOG_ADD_MESSAGE, record.toUnframedByteArray)
              index.put(encode(message_prefix, record.key), encode(pos))
            }
          }

          streams.using_queue_entry_stream { stream=>
            foreach[QueueEntryPB.Buffer](stream, QueueEntryPB.FACTORY) { record=>
              index.put(encode(queue_entry_prefix, record.queue_key, record.entry_seq), record.toUnframedByteArray)
            }
          }
        }

      }
      snapshot_index
      Success(Zilch)

    } catch {
      case x:Exception=>
        Failure(x.getMessage)
    }
  }
}
