package forge

import utest._
import TestUtil.test
import forge.define.Target
import forge.discover.Discovered
import forge.eval.Evaluator
import forge.util.OSet

object GraphTests extends TestSuite{

  val tests = Tests{


    val graphs = new TestGraphs()
    import graphs._

    'discovery{
      class CanNest{
        val single = test()
        val invisible: Any = test()
      }
      object outer {
        val single = test()
        val invisible: Any = test()
        object nested{
          val single = test()
          val invisible: Any = test()

        }
        val classInstance = new CanNest

      }
      val discovered = Discovered[outer.type].apply(outer).map(x => (x._1, x._3))
      val expected = Seq(
        (List("classInstance", "single"), outer.classInstance.single),
        (List("nested", "single"), outer.nested.single),
        (List("single"), outer.single)
      )
      assert(discovered == expected)
    }


    'topoSortedTransitiveTargets - {
      def check(targets: OSet[Target[_]], expected: OSet[Target[_]]) = {
        val result = Evaluator.topoSortedTransitiveTargets(targets).values
        TestUtil.checkTopological(result)
        assert(result == expected)
      }

      'singleton - check(
        targets = OSet(singleton.single),
        expected = OSet(singleton.single)
      )
      'pair - check(
        targets = OSet(pair.down),
        expected = OSet(pair.up, pair.down)
      )
      'anonTriple - check(
        targets = OSet(anonTriple.down),
        expected = OSet(anonTriple.up, anonTriple.down.inputs(0), anonTriple.down)
      )
      'diamond - check(
        targets = OSet(diamond.down),
        expected = OSet(diamond.up, diamond.left, diamond.right, diamond.down)
      )
      'anonDiamond - check(
        targets = OSet(diamond.down),
        expected = OSet(
          diamond.up,
          diamond.down.inputs(0),
          diamond.down.inputs(1),
          diamond.down
        )
      )
      'defCachedDiamond - check(
        targets = OSet(defCachedDiamond.down),
        expected = OSet(
          defCachedDiamond.up,
          defCachedDiamond.down.inputs(0),
          defCachedDiamond.down.inputs(1),
          defCachedDiamond.down
        )
      )
      'borkedCachedDiamond - {
        // Make sure these fail because `def`s without `Cacher` will re-evaluate
        // each time, returning different sets of targets.
        //
        // Maybe later we can convert them into compile errors somehow
        * - intercept[Throwable]{
          check(
            targets = OSet(borkedCachedDiamond1.down),
            expected = OSet(
              borkedCachedDiamond1.up,
              borkedCachedDiamond1.down.inputs(0),
              borkedCachedDiamond1.down.inputs(1),
              borkedCachedDiamond1.down
            )
          )
        }
        * - intercept[Throwable]{
          check(
            targets = OSet(borkedCachedDiamond2.down),
            expected = OSet(
              borkedCachedDiamond2.up,
              borkedCachedDiamond2.down.inputs(0),
              borkedCachedDiamond2.down.inputs(1),
              borkedCachedDiamond2.down
            )
          )
        }
        * - intercept[Throwable]{
          check(
            targets = OSet(borkedCachedDiamond3.down),
            expected = OSet(
              borkedCachedDiamond3.up,
              borkedCachedDiamond3.down.inputs(0),
              borkedCachedDiamond3.down.inputs(1),
              borkedCachedDiamond3.down
            )
          )
        }
      }
      'bigSingleTerminal - {
        val result = Evaluator.topoSortedTransitiveTargets(OSet(bigSingleTerminal.j)).values
        TestUtil.checkTopological(result)
        assert(result.size == 28)
      }
    }

    'groupAroundNamedTargets - {
      def check[T: Discovered, R <: Target[Int]](base: T,
                               target: R,
                               expected: OSet[(OSet[R], Int)]) = {

        val mapping = Discovered.mapping(base)
        val topoSortedTransitive = Evaluator.topoSortedTransitiveTargets(OSet(target))

        val grouped = Evaluator.groupAroundNamedTargets(topoSortedTransitive, mapping)
        val flattened = OSet.from(grouped.values().flatMap(_.items))

        TestUtil.checkTopological(flattened)
        for(((expectedPresent, expectedSize), i) <- expected.items.zipWithIndex){
          val grouping = grouped.lookupKey(i)
          assert(
            grouping.size == expectedSize,
            grouping.filter(mapping.contains) == expectedPresent
          )
        }
      }
      'singleton - check(
        singleton,
        singleton.single,
        OSet(OSet(singleton.single) -> 1)
      )
      'pair - check(
        pair,
        pair.down,
        OSet(OSet(pair.up) -> 1, OSet(pair.down) -> 1)
      )
      'anonTriple - check(
        anonTriple,
        anonTriple.down,
        OSet(OSet(anonTriple.up) -> 1, OSet(anonTriple.down) -> 2)
      )
      'diamond - check(
        diamond,
        diamond.down,
        OSet(
          OSet(diamond.up) -> 1,
          OSet(diamond.left) -> 1,
          OSet(diamond.right) -> 1,
          OSet(diamond.down) -> 1
        )
      )

      'defCachedDiamond - check(
        defCachedDiamond,
        defCachedDiamond.down,
        OSet(
          OSet(defCachedDiamond.up) -> 1,
          OSet(defCachedDiamond.left) -> 1,
          OSet(defCachedDiamond.right) -> 1,
          OSet(defCachedDiamond.down) -> 1
        )
      )

      'anonDiamond - check(
        anonDiamond,
        anonDiamond.down,
        OSet(
          OSet(anonDiamond.up) -> 1,
          OSet(anonDiamond.down) -> 3
        )
      )
      'bigSingleTerminal - check(
        bigSingleTerminal,
        bigSingleTerminal.j,
        OSet(
          OSet(bigSingleTerminal.a) -> 3,
          OSet(bigSingleTerminal.b) -> 2,
          OSet(bigSingleTerminal.e) -> 9,
          OSet(bigSingleTerminal.i) -> 6,
          OSet(bigSingleTerminal.f) -> 4,
          OSet(bigSingleTerminal.j) -> 4
        )
      )
    }

    'labeling - {

      def check[T: Discovered](base: T, t: Target[_], relPath: Option[String]) = {


        val names: Seq[(Target[_], Seq[String])] = Discovered.mapping(base).mapValues(_.segments).toSeq
        val nameMap = names.toMap

        val targetLabel = nameMap.get(t).map(_.mkString("."))
        assert(targetLabel == relPath)
      }
      'singleton - check(singleton, singleton.single, Some("single"))
      'pair - {
        check(pair, pair.up, Some("up"))
        check(pair, pair.down, Some("down"))
      }

      'anonTriple - {
        check(anonTriple, anonTriple.up, Some("up"))
        check(anonTriple, anonTriple.down.inputs(0), None)
        check(anonTriple, anonTriple.down, Some("down"))
      }

      'diamond - {
        check(diamond, diamond.up, Some("up"))
        check(diamond, diamond.left, Some("left"))
        check(diamond, diamond.right, Some("right"))
        check(diamond, diamond.down, Some("down"))
      }

      'anonDiamond - {
        check(anonDiamond, anonDiamond.up, Some("up"))
        check(anonDiamond, anonDiamond.down.inputs(0), None)
        check(anonDiamond, anonDiamond.down.inputs(1), None)
        check(anonDiamond, anonDiamond.down, Some("down"))
      }

    }

  }
}
