package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server.PathMatcher.{ Matched, Unmatched }
import mesosphere.UnitTest
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.GroupCreation

class DirectivesTest extends UnitTest with GroupCreation {
  import Directives._
  import PathId.StringPathId

  "ExistingAppPathId matcher" should {
    val app1 = AppDefinition("/test/group1/app1".toPath)
    val app2 = AppDefinition("/test/group2/app2".toPath)
    val rootGroup = createRootGroup(
      groups = Set(
        createGroup("/test".toPath, groups = Set(
          createGroup("/test/group1".toPath, Map(app1.id -> app1)),
          createGroup("/test/group2".toPath, Map(app2.id -> app2))
        ))))

    "not match groups" in {
      ExistingAppPathId(rootGroup)(Path("test/group1")) shouldBe (
        Unmatched)
    }

    "match apps that exist" in {
      ExistingAppPathId(rootGroup)(Path("test/group1/app1")) shouldBe (
        Matched(Path(""), Tuple1("/test/group1/app1".toPath)))
    }

    "match not match apps that don't exist" in {
      ExistingAppPathId(rootGroup)(Path("test/group1/app3")) shouldBe (
        Unmatched)
    }

    "leave path components after matching appIds unconsumed" in {
      ExistingAppPathId(rootGroup)(Path("test/group1/app1/restart/ponies")) shouldBe (
        Matched(Path("/restart/ponies"), Tuple1("/test/group1/app1".toPath)))
    }
  }
}
