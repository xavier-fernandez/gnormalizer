package gnormalizer.parsers

import babel.graph.Edge
import cats.effect.IO
import fs2.Stream
import org.specs2.ScalaCheck
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragments

/**
  * Test for @see [[EdgeListParser]]
  */
class EdgeListParserSpec extends Specification with ScalaCheck {

  /**
    * Normalizes a ScalaCheck node string to do have any invalid parameter such as,
    * white spaces within a [[Edge]], '\n' characters or commented
    * lines.
    */
  private[this] def normalizeScalacheckNodeString(parser: EdgeListParser, node: String) = {
    val normalizedTestNode = {
      node.trim
        .replaceAll(" ", "")
        .replaceAll("\n", "")
        .replaceAll("\r", "")
        .replaceAll("\u000b", "")
        .replaceAll("\u000c", "")
        .replaceAll("\u0009", "")
    }
    normalizedTestNode match {
      case "" => "TestNode"
      case _ if parser.commentedLinesStartCharacters.exists(normalizedTestNode.startsWith) =>
        s"V$normalizedTestNode"
      case _ => normalizedTestNode
    }
  }

  private[this] def prepareScalaCheckTest(parser: EdgeListParser, a: String, b: String): String = {
    val normalizedA = normalizeScalacheckNodeString(parser, a)
    val normalizedB = normalizeScalacheckNodeString(parser, b)
    // In this test Node 'A' and 'B' cannot be equal
    if (normalizedA.equals(normalizedB)) {
      s"$normalizedA ${normalizedB}2"
    } else {
      s"$normalizedA $normalizedB"
    }
  }

  /**
    * Checks if the parser result is valid or not.
    */
  private[this] def checkResult(parser: EdgeListParser,
                                input: Stream[IO, String],
                                numberOfEdges: Int): MatchResult[_] = {
    parser.toEdgeStream(input).compile.toList.unsafeRunSync().size must beEqualTo(numberOfEdges)
  }

  "toStream() method" should {
    val parser: EdgeListParser = new EdgeListParser()
    "Must work when inputting a Stream with no elements" in {
      val emptyEdgeStream = Stream.eval(IO.pure(""))
      checkResult(parser, emptyEdgeStream, 0)
    }
    "Must work when inputting with edges in numerical input edges" in {
      prop((a: Long, b: Long) => {
        parser
          .toEdgeStream(Stream.apply(s"$a $b"))
          .compile
          .toList
          .unsafeRunSync()
          .size must beEqualTo(1L)
      })
    }
    "Must work when inputting edges in non-numerical input edges" in {
      prop((sourceNode: String, targetNode: String) => {
        // Empty nodes are obviously not supported, so a 'V' prefix has being added.
        val inputEdge = prepareScalaCheckTest(parser, sourceNode, targetNode)
        val singleEdgeStream = Stream.eval(IO.pure(inputEdge))
        checkResult(parser, singleEdgeStream, 1)
      })
    }
    parser.commentedLinesStartCharacters.foldLeft(Fragments()) { (acc, commentStart) =>
      acc.append(s"It must ignore edges starting with '$commentStart'" in {
        prop((inputNodes: String) => {
          val normalizedInputString = normalizeScalacheckNodeString(parser, inputNodes)
          val inputEdge = s"$commentStart$normalizedInputString"
          val singleEdgeStream = Stream.eval(IO.pure(inputEdge))
          checkResult(parser, singleEdgeStream, 0)
        })
      })
    }
    "Must work when inputting two valid Strings divided by several whitespaces" in {
      prop((a: String, b: String, numberWhitespaces: Byte) => {
        val normalizedA = normalizeScalacheckNodeString(parser, a)
        val normalizedB = normalizeScalacheckNodeString(parser, b)
        val whitespaces: String = {
          " " * numberWhitespaces match {
            case "" | " " => "  "
            case severalWhitespaces => severalWhitespaces
          }
        }
        val inputEdge = s"$normalizedA$whitespaces$normalizedB"
        checkResult(parser, Stream.eval(IO.pure(inputEdge)), 1)
      })
    }
    "When inputting multiple valid inputs, converts all of them to Edges" in {
      val numberOfInputEdges = 1000

      val expectation: Stream[IO, String] = {
        (0 until numberOfInputEdges)
          .map(i => s"$i $i")
          .map(a => Stream.eval(IO.pure(a)))
          .reduceLeft(_ ++ _)
      }
      checkResult(parser, expectation, numberOfInputEdges)
    }
    "When inputting an invalid value, return a failed Stream" in {
      val invalidInputString: String = "a b c" // 3 nodes
      val invalidInput: Stream[IO, String] = Stream.eval(IO.pure(invalidInputString))
      val stream: Stream[IO, Edge] = parser.toEdgeStream(invalidInput)
      stream.compile.drain.unsafeRunSync() must throwA[IllegalArgumentException]
    }
  }

  "mappingsStream() method" in {
    "Returns an empty mapping Stream when no elements were converted with a specific parser" in {
      val emptyParser: EdgeListParser = new EdgeListParser()
      emptyParser.mappingsStream() must beEmpty
    }
    "Returns the inserted Node mappings successfully on a single value Stream" in {
      prop((a: String, b: String) => {
        // Generates the parser to test
        val testParser: EdgeListParser = new EdgeListParser()
        // Generate the test edges
        val testEdge = prepareScalaCheckTest(testParser, a, b)
        // Generates the parser input
        val input: Stream[IO, String] = Stream.eval(IO.pure(testEdge))
        // Expectation
        testParser.toEdgeStream(input).compile.drain.unsafeRunSync()
        testParser.mappingsStream().size must beEqualTo(2)
      })
    }
    "Returns the inserted Node mappings when the Stream contains multiple values" in {
      // Generates the parser to test
      val parser: EdgeListParser = new EdgeListParser()
      // Input edges
      val firstEdge: String = s"TestEdgeSource TestEdgeTarget"
      val edges: Seq[String] = (1 until 1000).map(_.toString).map(index => s"$index ${index}A")
      // Parses the edges in the stream
      parser
        .toEdgeStream(edges.foldLeft(Stream.eval[IO, String](IO.pure(firstEdge))) {
          (acc, edgeString) =>
            acc ++ Stream.eval(IO.pure(edgeString))
        })
        .compile
        .drain
        .unsafeRunSync()

      // Number of edges * 2 (Source target)
      val expectedNumberEdges: Int = 2 * edges.size + 2
      // Expectation
      parser.mappingsStream().size must beEqualTo(expectedNumberEdges)
    }
  }
}
