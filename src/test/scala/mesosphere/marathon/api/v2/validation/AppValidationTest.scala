package mesosphere.marathon
package api.v2.validation

import mesosphere.{ UnitTest, ValidationTestLike }
import mesosphere.marathon.raml._

class AppValidationTest extends UnitTest with ValidationTestLike {

  "network validation" when {
    implicit val basicValidator = AppValidation.validateCanonicalAppAPI(Set.empty)

    def networkedApp(portMappings: Seq[ContainerPortMapping], networks: Seq[Network]) = {
      App(
        id = "/foo",
        cmd = Some("bar"),
        networks = networks,
        container = Some(Container(`type` = EngineType.Mesos, portMappings = Some(portMappings))))
    }

    def containerNetworkedApp(portMappings: Seq[ContainerPortMapping], networkCount: Int = 1) =
      networkedApp(
        portMappings,
        networks = 1.to(networkCount).map { i => Network(mode = NetworkMode.Container, name = Some(i.toString)) })

    "multiple container networks are specified for an app" should {

      "require networkNames for hostPort to containerPort mapping" in {
        val badApp = containerNetworkedApp(
          Seq(ContainerPortMapping(hostPort = Option(0))), networkCount = 2)

        shouldViolate(badApp, "/container/portMappings(0)", AppValidationMessages.NetworkNameRequiredForMultipleContainerNetworks)
      }

      "allow portMappings that don't declare hostPort nor networkNames" in {
        val badApp = containerNetworkedApp(
          Seq(ContainerPortMapping()), networkCount = 2)
        shouldSucceed(badApp)
      }

      "allow portMappings that both declare a hostPort and a networkNames" in {
        shouldSucceed(containerNetworkedApp(Seq(
          ContainerPortMapping(
            hostPort = Option(0),
            networkNames = List("1"))), networkCount = 2))
      }
    }

    "single container network" should {

      "consider a valid portMapping with a name as valid" in {
        shouldSucceed(
          containerNetworkedApp(
            Seq(
              ContainerPortMapping(
                hostPort = Some(80),
                containerPort = 80,
                networkNames = List("1")))))
      }

      "consider a portMapping with a hostPort and two valid networkNames as invalid" in {
        val app = containerNetworkedApp(
          Seq(
            ContainerPortMapping(
              hostPort = Some(80),
              containerPort = 80,
              networkNames = List("1", "2"))),
          networkCount = 3)
        shouldViolate(app, "/container/portMappings(0)", AppValidationMessages.NetworkNameRequiredForMultipleContainerNetworks)
      }

      "consider a portMapping with no name as valid" in {
        shouldSucceed(
          containerNetworkedApp(
            Seq(
              ContainerPortMapping(
                hostPort = Some(80),
                containerPort = 80,
                networkNames = Nil))))
      }

      "consider a portMapping without a hostport as valid" in {
        shouldSucceed(
          containerNetworkedApp(
            Seq(
              ContainerPortMapping(
                hostPort = None))))
      }

      "consider portMapping with zero hostport as valid" in {
        shouldSucceed(
          containerNetworkedApp(
            Seq(
              ContainerPortMapping(
                containerPort = 80,
                hostPort = Some(0)))))
      }

      "consider portMapping with a non-matching network name as invalid" in {
        val app =
          containerNetworkedApp(
            Seq(
              ContainerPortMapping(
                containerPort = 80,
                hostPort = Some(80),
                networkNames = List("undefined-network-name"))))
        shouldViolate(app, "/container/portMappings(0)/networkNames(0)", "is not one of (1)")
      }

      "consider portMapping without networkNames nor hostPort as valid" in {
        shouldSucceed(
          containerNetworkedApp(
            Seq(
              ContainerPortMapping(
                containerPort = 80,
                hostPort = None,
                networkNames = Nil))))
      }
    }

    "general port validation" in {
      val app =
        containerNetworkedApp(
          Seq(
            ContainerPortMapping(
              name = Some("name"),
              hostPort = Some(123)),
            ContainerPortMapping(
              name = Some("name"),
              hostPort = Some(123))))
      shouldViolate(app, "/container/portMappings", "Port names must be unique.")
    }

    "missing hostPort is allowed for bridge networking (so we can normalize it)" in {
      // This isn't _actually_ allowed; we expect that normalization will replace the None to a Some(0) before
      // converting to an AppDefinition, in order to support legacy API
      shouldSucceed(networkedApp(
        portMappings = Seq(ContainerPortMapping(
          containerPort = 8080,
          hostPort = None,
          servicePort = 0,
          name = Some("foo"))),
        networks = Seq(Network(mode = NetworkMode.ContainerBridge, name = None))
      ))
    }

  }
}
