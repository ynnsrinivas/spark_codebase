package com.cloudwick.spark.examples.streaming.local

import java.io.File
import java.nio.charset.Charset

import com.google.common.io.Files
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.StreamingContext._
import org.apache.spark.streaming.{Seconds, StreamingContext, Time}
import org.apache.spark.{Logging, SparkConf}

/**
 * Counts words in text encoded with UTF8 received from the network every second.
 *
 * Usage: RecoverableNetworkWordCount <hostname> <port> <checkpoint-directory> <output-file>
 *   <hostname> and <port> describe the TCP server that Spark Streaming would connect to receive
 *   data. <checkpoint-directory> directory to HDFS-compatible file system which checkpoint data
 *   <output-file> file to which the word counts will be appended
 *
 * <checkpoint-directory> and <output-file> must be absolute paths
 *
 * To run this on your local machine, you need to first run a NetCat server
 *
 *      `nc -lk 9999`
 *
 * and run the program
 *
 *      `spark-submit --class com.cloudwick.spark.examples.streaming.local.RecoverableNetworkWordCount
 *        --master local[*] path-to-jar [args]`
 *
 * If the directory ~/checkpoint/ does not exist (e.g. running for the first time), it will create
 * a new StreamingContext (will print "Creating new context" to the console). Otherwise, if
 * checkpoint data exists in ~/checkpoint/, then it will create StreamingContext from
 * the checkpoint data.
 */
object RecoverableNetworkWordCount extends App with Logging {

  def createContext(ip: String, port: Int, outputPath: String, checkpointDirectory: String)
  : StreamingContext = {
    println("Creating new context")
    val outputFile = new File(outputPath)
    if (outputFile.exists()) outputFile.delete()
    val sparkConf = new SparkConf().setAppName("RecoverableNetworkWordCount")
    // Create the context with a 1 second batch size
    val ssc = new StreamingContext(sparkConf, Seconds(1))
    ssc.checkpoint(checkpointDirectory)

    // Create a socket stream on target ip:port and count the
    // words in input stream of \n delimited text (eg. generated by 'nc')
    val lines = ssc.socketTextStream(ip, port)
    val words = lines.flatMap(_.split(" "))
    val wordCounts = words.map(x => (x, 1)).reduceByKey(_ + _)
    wordCounts.foreachRDD((rdd: RDD[(String, Int)], time: Time) => {
      val counts = "Counts at time " + time + " " + rdd.collect().mkString("[", ", ", "]")
      println(counts)
      println("Appending to " + outputFile.getAbsolutePath)
      Files.append(counts + "\n", outputFile, Charset.defaultCharset())
    })
    ssc
  }

  if (args.length != 4) {
    System.err.println("You arguments were " + args.mkString("[", ", ", "]"))
    System.err.println(
      """
        |Usage: RecoverableNetworkWordCount <hostname> <port> <checkpoint-directory>
        |     <output-file>. <hostname> and <port> describe the TCP server that Spark
        |     Streaming would connect to receive data. <checkpoint-directory> directory to
        |     HDFS-compatible file system which checkpoint data <output-file> file to which the
        |     word counts will be appended
        |
        |In local mode, <master> should be 'local[n]' with n > 1
        |Both <checkpoint-directory> and <output-file> must be absolute paths
      """.stripMargin
    )
    System.exit(1)
  }
  val Array(ip, port, checkpointDirectory, outputPath) = args
  val ssc = StreamingContext.getOrCreate(checkpointDirectory,
    () => {
      createContext(ip, port.toInt, outputPath, checkpointDirectory)
    })
  ssc.start()
  ssc.awaitTermination()
}