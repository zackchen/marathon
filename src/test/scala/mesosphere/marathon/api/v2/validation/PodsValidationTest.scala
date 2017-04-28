package mesosphere.marathon
package api.v2.validation

import com.wix.accord.Validator
import mesosphere.{ UnitTest, ValidationTestLike }
import mesosphere.marathon.raml.{ Constraint, ConstraintOperator, Endpoint, Network, NetworkMode, Pod, PodContainer, Resources, Volume, VolumeMount }
import mesosphere.marathon.util.SemanticVersion

class PodsValidationTest extends UnitTest with ValidationTestLike with PodsValidation with SchedulingValidation {

  "A pod definition" should {

    "be rejected if the id is empty" in new Fixture {
      shouldViolate(validPod.copy(id = "/"), "/id", "Path must contain at least one path element")
    }

    "be rejected if the id is not absolute" in new Fixture {
      shouldViolate(validPod.copy(id = "some/foo"), "/id", "Path needs to be absolute")
    }

    "be rejected if a defined user is empty" in new Fixture {
      shouldViolate(validPod.copy(user = Some("")), "/user", "must not be empty")
    }

    "be rejected if no container is defined" in new Fixture {
      shouldViolate(validPod.copy(containers = Seq.empty), "/containers", "must not be empty")
    }

    "be rejected if container names are not unique" in new Fixture {
      shouldViolate(validPod.copy(containers = Seq(validContainer, validContainer)), "/containers", PodsValidationMessages.ContainerNamesMustBeUnique)
    }

    "be rejected if endpoint names are not unique" in new Fixture {
      val endpoint1 = Endpoint("endpoint", hostPort = Some(123))
      val endpoint2 = Endpoint("endpoint", hostPort = Some(124))
      private val invalid = validPod.copy(containers = Seq(validContainer.copy(endpoints = Seq(endpoint1, endpoint2))))
      shouldViolate(invalid, "/", PodsValidationMessages.EndpointNamesMustBeUnique)
    }

    "be rejected if endpoint host ports are not unique" in new Fixture {
      val endpoint1 = Endpoint("endpoint1", hostPort = Some(123))
      val endpoint2 = Endpoint("endpoint2", hostPort = Some(123))
      private val invalid = validPod.copy(containers = Seq(validContainer.copy(endpoints = Seq(endpoint1, endpoint2))))
      shouldViolate(invalid, "/", PodsValidationMessages.HostPortsMustBeUnique)
    }

    "be rejected if endpoint container ports are not unique" in new Fixture {
      val endpoint1 = Endpoint("endpoint1", containerPort = Some(123))
      val endpoint2 = Endpoint("endpoint2", containerPort = Some(123))
      private val invalid = validPod.copy(
        networks = Seq(Network(mode = NetworkMode.Container)),
        containers = Seq(validContainer.copy(endpoints = Seq(endpoint1, endpoint2)))
      )
      shouldViolate(invalid, "/", PodsValidationMessages.ContainerPortsMustBeUnique)
    }

    "be rejected if volume names are not unique" in new Fixture {
      val volume = Volume("volume", host = Some("/foo"))
      val volumeMount = VolumeMount(volume.name, "/bla")
      private val invalid = validPod.copy(
        volumes = Seq(volume, volume),
        containers = Seq(validContainer.copy(volumeMounts = Seq(volumeMount)))
      )
      shouldViolate(invalid, "/volumes", PodsValidationMessages.VolumeNamesMustBeUnique)
    }
  }

  "A constraint definition" should {

    "MaxPer is accepted with an integer value" in {
      complyWithConstraintRules(Constraint("foo", ConstraintOperator.MaxPer, Some("3"))).isSuccess shouldBe true
    }

    "MaxPer is rejected with no value" in {
      complyWithConstraintRules(Constraint("foo", ConstraintOperator.MaxPer)).isSuccess shouldBe false
    }
  }

  class Fixture {
    val validContainer = PodContainer(
      name = "ct1",
      resources = Resources()
    )
    val validPod = Pod(
      id = "/some/pod",
      containers = Seq(validContainer),
      networks = Seq(Network(mode = NetworkMode.Host))
    )
    implicit val validator: Validator[Pod] = podDefValidator(Set.empty, SemanticVersion.zero)
  }

  "network validation" when {
    implicit val validator: Validator[Pod] = podDefValidator(Set.empty, SemanticVersion.zero)

    def podContainer(name: String = "ct1", resources: Resources = Resources(), endpoints: Seq[Endpoint] = Nil) =
      PodContainer(
        name = name,
        resources = resources,
        endpoints = endpoints)

    def containerNetworkedPod(containers: Seq[PodContainer], networkCount: Int = 1) =
      Pod(
        id = "/foo",
        networks = 1.to(networkCount).map(i => Network(mode = NetworkMode.Container, name = Some(i.toString))),
        containers = containers)

    "multiple container networks are specified for a pod" should {

      "require networkNames for containerPort to hostPort mapping" in {
        val badApp = containerNetworkedPod(
          Seq(podContainer(endpoints = Seq(Endpoint("endpoint", containerPort = Some(80), hostPort = Option(0))))),
          networkCount = 2)

        validator(badApp).isFailure shouldBe true
      }

      "allow portMappings that don't declare hostPort nor networkNames" in {
        val app = containerNetworkedPod(
          Seq(podContainer(endpoints = Seq(Endpoint("endpoint", containerPort = Some(80))))),
          networkCount = 2)
        shouldSucceed(app)
      }

      "allow portMappings that both declare a hostPort and a networkNames" in {
        val app = containerNetworkedPod(
          Seq(podContainer(endpoints = Seq(
            Endpoint(
              "endpoint",
              hostPort = Option(0),
              containerPort = Some(80),
              networkNames = List("1"))))),
          networkCount = 2)
        shouldSucceed(app)
      }
    }

    "single container network" should {

      "consider a valid portMapping with a networkNames as valid" in {
        val pod = containerNetworkedPod(Seq(
          podContainer(endpoints = Seq(
            Endpoint(
              "endpoint",
              hostPort = Some(80),
              containerPort = Some(80),
              networkNames = List("1"))))))
        shouldSucceed(pod)
      }

      "consider a portMapping with a host port and two valid networkNames as invalid" in {
        val pod = containerNetworkedPod(Seq(
          podContainer(endpoints = Seq(
            Endpoint(
              "endpoint",
              hostPort = Some(80),
              containerPort = Some(80),
              networkNames = List("1", "2"))))))
        shouldSucceed(pod)
      }

      "consider a portMapping with no networkNames as valid" in {
        val pod = containerNetworkedPod(Seq(
          podContainer(endpoints = Seq(
            Endpoint(
              "endpoint",
              hostPort = Some(80),
              containerPort = Some(80),
              networkNames = Nil)))))
        shouldSucceed(pod)
      }

      "maybe consider a portMapping without hostport as valid" in {
        val pod = containerNetworkedPod(Seq(
          podContainer(endpoints = Seq(
            Endpoint(
              "endpoint",
              hostPort = None,
              containerPort = Some(80),
              networkNames = Nil)))))
        shouldSucceed(pod)
      }

      "consider portMapping with zero hostport as valid" in {
        val pod =
          containerNetworkedPod(Seq(
            podContainer(endpoints = Seq(
              Endpoint(
                "endpoint",
                containerPort = Some(80),
                hostPort = Some(0))))))
        shouldSucceed(pod)
      }

      "consider portMapping with a non-matching network name as invalid" in {
        val pod =
          containerNetworkedPod(Seq(
            podContainer(endpoints = Seq(
              Endpoint(
                "endpoint",
                containerPort = Some(80),
                hostPort = Some(80),
                networkNames = List("invalid-network-name"))))))
        shouldViolate(pod, "", "")
      }

      "consider portMapping without networkNames nor hostPort as valid" in {
        val pod = containerNetworkedPod(Seq(
          podContainer(endpoints = Seq(
            Endpoint(
              "endpoint",
              containerPort = Some(80),
              hostPort = None,
              networkNames = Nil)))))
        shouldSucceed(pod)
      }
    }

    "require that hostPort is unique" in {
      val pod =
        containerNetworkedPod(Seq(
          podContainer(endpoints = Seq(
            Endpoint(
              "name",
              hostPort = Some(123)),
            Endpoint(
              "name",
              hostPort = Some(123))))))

      shouldViolate(pod, "/containers(0)/endpoints(0)/containerPort", "is required when using container-mode networking")
      shouldViolate(pod, "/", PodsValidationMessages.EndpointNamesMustBeUnique)
      shouldViolate(pod, "/", PodsValidationMessages.HostPortsMustBeUnique)
    }
  }
}
