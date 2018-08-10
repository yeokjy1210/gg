package com.codacy.analysis.core.upload

import java.nio.file.{Path, Paths}

import better.files.File
import cats.implicits._
import com.codacy.analysis.core.clients.CodacyClient
import com.codacy.analysis.core.clients.api.{ProjectConfiguration, ToolConfiguration, ToolPattern}
import com.codacy.analysis.core.model._
import com.codacy.analysis.core.utils.TestUtils._
import com.codacy.plugins.api.metrics.LineComplexity
import io.circe.generic.auto._
import io.circe.parser
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.specs2.control.NoLanguageFeatures
import org.specs2.matcher.{FutureMatchers, MatchResult}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragment

import scala.concurrent.Future
import scala.concurrent.duration._

class ResultsUploaderSpec extends Specification with NoLanguageFeatures with Mockito with FutureMatchers {

  private val commitUuid = "9232dbdcae98b19412c8dd98c49da8c391612bfa"
  private val tool = "eslint"
  private val otherTool = "rubocop"
  private val otherFilenames = Set(Paths.get("src/things/Clazz.scala"))
  private val batchSize = 10

  "ResultsUploader" should {

    "not create the uploader if upload is not requested" in {
      val codacyClient = mock[CodacyClient]
      ResultsUploader(Option(codacyClient), upload = false, Option(commitUuid), Option(batchSize)) must beRight(
        Option.empty[ResultsUploader])
    }

    "fail to create the uploader if the client is not passed" in {
      ResultsUploader(Option.empty[CodacyClient], upload = true, Option(commitUuid), Option(batchSize)) must beLeft(
        "No credentials found.")
    }

    "fail to create the uploader if the commit is not passed" in {
      val codacyClient = mock[CodacyClient]
      ResultsUploader(Option(codacyClient), upload = true, Option.empty[String], Option(batchSize)) must beLeft(
        "No commit found.")
    }

    val exampleResultsEither = for {
      resultsJson <- parser.parse(
        File.resource("com/codacy/analysis/core/upload/cli-output-monogatari-eslint-1.json").contentAsString)
      exampleResults <- resultsJson.as[Set[ToolResult]]
    } yield exampleResults

    val exampleResults = exampleResultsEither.right.get
    testBatchSize(exampleResults)(-10, "sending batches of -10 results in each payload - should use default", 1)
    testBatchSize(exampleResults)(0, "sending batches of 0 results in each payload - should use default", 1)
    testBatchSize(exampleResults)(5, "sending batches of 5 results in each payload - should use 5", 13)
    testBatchSize(exampleResults)(
      5000,
      "sending batches of 5000 (> results.length) results in each payload - should use results.length",
      1)

    "send metrics" in {
      val codacyClient = mock[CodacyClient]

      val language = "klingon"
      val commitUuid = "12345678900987654321"

      when(
        codacyClient.sendRemoteMetrics(
          ArgumentMatchers.eq(language),
          ArgumentMatchers.eq(commitUuid),
          ArgumentMatchers.any[Set[MetricsResult]])).thenAnswer((invocation: InvocationOnMock) => {
        Future.successful(().asRight[String])
      })

      when(codacyClient.getRemoteConfiguration).thenReturn(ProjectConfiguration(
        Set.empty,
        Some(Set.empty),
        Set.empty,
        Set.empty)
        .asRight[String])

      when(codacyClient.sendEndOfResults(commitUuid)).thenReturn(Future(().asRight[String]))

      val uploader: ResultsUploader =
        ResultsUploader(Option(codacyClient), upload = true, Some(commitUuid), None).right.get.get

      def testFileMetrics(i: Int) = {
        FileMetrics(
          filename = Paths.get(s"some/path/file$i"),
          complexity = Some(10),
          loc = Some(11),
          cloc = Some(12),
          nrMethods = Some(14),
          nrClasses = Some(15),
          lineComplexities = Set(LineComplexity(1, 2), LineComplexity(3, 4), LineComplexity(5, 6)))
      }

      val testMetrics = Seq(
        ResultsUploader.MetricsResults(
          language,
          Set(MetricsResult(Set(testFileMetrics(1), testFileMetrics(2), testFileMetrics(3)), None))))

      uploader.sendResults(Seq.empty, testMetrics) must beRight.awaitFor(10.seconds)

      there were no(codacyClient).sendRemoteResults(ArgumentMatchers.any[String], ArgumentMatchers.any[String], ArgumentMatchers.any[Set[FileResults]])

      there was one(codacyClient).sendRemoteMetrics(ArgumentMatchers.eq(language), ArgumentMatchers.eq(commitUuid), ArgumentMatchers.any[Set[MetricsResult]])

      there was one(codacyClient).sendEndOfResults(commitUuid)
    }

  }

  private def testBatchSize(
    exampleResults: Set[ToolResult])(batchSize: Int, message: String, expectedNrOfBatches: Int): Fragment = {
    val esLintPatternsInternalIds = Set("ESLint_semi", "ESLint_no-undef", "ESLint_indent", "ESLint_no-empty")

    s"analyse a javascript project with eslint, $message".stripMargin in {
      val toolPatterns = esLintPatternsInternalIds.map { patternId =>
        ToolPattern(patternId, Set.empty)
      }
      val codacyClient = mock[CodacyClient]
      val uploader: ResultsUploader =
        ResultsUploader(Option(codacyClient), upload = true, Option(commitUuid), Option(batchSize)).right.get.get

      when(
        codacyClient.sendRemoteResults(
          ArgumentMatchers.eq(tool),
          ArgumentMatchers.eq(commitUuid),
          ArgumentMatchers.any[Set[FileResults]])).thenAnswer((invocation: InvocationOnMock) => {
        Future.successful(().asRight[String])
      })
      when(
        codacyClient.sendRemoteResults(
          ArgumentMatchers.eq(otherTool),
          ArgumentMatchers.eq(commitUuid),
          ArgumentMatchers.any[Set[FileResults]])).thenAnswer((invocation: InvocationOnMock) => {
        Future.successful(().asRight[String])
      })

      when(codacyClient.getRemoteConfiguration).thenReturn(getMockedRemoteConfiguration(toolPatterns))

      when(codacyClient.sendEndOfResults(commitUuid)).thenReturn(Future(().asRight[String]))

      val filenames: Set[Path] = exampleResults.map {
        case i: Issue      => i.filename
        case fe: FileError => fe.filename
      }(collection.breakOut)

      // scalafix:off NoInfer.any
      uploader.sendResults(
        Seq(
          ResultsUploader.ToolResults(tool, filenames, exampleResults),
          ResultsUploader.ToolResults(otherTool, otherFilenames, Set.empty[ToolResult])),
        Seq.empty) must beRight.awaitFor(10.minutes)
      // scalafix:on NoInfer.any

      verifyNumberOfCalls(codacyClient, tool, commitUuid, expectedNrOfBatches)
    }
  }

  private def getMockedRemoteConfiguration(toolPatterns: Set[ToolPattern]): Either[String, ProjectConfiguration] = {
    ProjectConfiguration(
      Set.empty,
      Some(Set.empty),
      Set.empty,
      Set(ToolConfiguration("cf05f3aa-fd23-4586-8cce-5368917ec3e5", isEnabled = true, notEdited = false, toolPatterns)))
      .asRight[String]
  }

  private def verifyNumberOfCalls(codacyClient: CodacyClient,
                                  tool: String,
                                  commitUuid: String,
                                  expectedNrOfBatches: Int): MatchResult[Future[Either[String, Unit]]] = {
    there was expectedNrOfBatches
      .times(codacyClient)
      .sendRemoteResults(
        ArgumentMatchers.eq(tool),
        ArgumentMatchers.eq(commitUuid),
        ArgumentMatchers.any[Set[FileResults]])

    there were no(codacyClient).sendRemoteMetrics(
      ArgumentMatchers.any[String],
      ArgumentMatchers.any[String],
      ArgumentMatchers.any[Set[MetricsResult]])

    there was one(codacyClient).sendEndOfResults(ArgumentMatchers.eq(commitUuid))
  }

}
