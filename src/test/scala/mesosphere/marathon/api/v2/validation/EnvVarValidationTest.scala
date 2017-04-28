package mesosphere.marathon
package api.v2.validation

import com.wix.accord.Validator
import com.wix.accord.dsl._
import mesosphere.marathon.raml.{ EnvVarValueOrSecret, Environment }
import mesosphere.{ UnitTest, ValidationTestLike }

class EnvVarValidationTest extends UnitTest with ValidationTestLike {

  import EnvVarValidationMessages._

  "EnvVarValidation" when {
    "an environment is validated" should {

      def compliantEnv(title: String, m: Map[String, EnvVarValueOrSecret], strictNameValidation: Boolean = true): Unit = {
        s"$title is compliant with validation rules" in new WithoutSecrets(strictNameValidation) {
          shouldSucceed(m)
        }
      }

      behave like compliantEnv("empty env", Map.empty)
      behave like compliantEnv("singleton env", Environment("foo" -> "bar"))
      behave like compliantEnv("mixed case env", Environment("foo" -> "bar", "FOO" -> "BAR"))
      behave like compliantEnv("underscore env", Environment("_" -> "x"))
      behave like compliantEnv("alpha numerical env", Environment("a_1_" -> "x"))
      behave like compliantEnv("whitespace env", Environment(" " -> "x"), strictNameValidation = false)
      behave like compliantEnv("hyphen env", Environment("-" -> "x"), strictNameValidation = false)
      behave like compliantEnv("numerical env", Environment("9" -> "x"), strictNameValidation = false)

      "fail with a numerical env variable name" in new WithoutSecrets {
        shouldViolate(Wrapper(Environment("9" -> "x")), "/env(9)", MustContainOnlyAlphanumeric)
      }

      def failsWhenExpected(subtitle: String, strictNameValidation: Boolean): Unit = {
        s"fail with empty variable name $subtitle" in new WithoutSecrets(strictNameValidation) {
          shouldViolate(Wrapper(Environment("" -> "x")), "/env()", "must not be empty")
          if (strictNameValidation) shouldViolate(Wrapper(Environment("" -> "x")), "/env()", MustContainOnlyAlphanumeric)
        }

        s"fail with too long variable name $subtitle" in new WithoutSecrets(strictNameValidation) {
          val name = ("x" * 255)
          if (strictNameValidation) {
            shouldViolate(Wrapper(Environment(name -> "x")), s"/env($name)", MustContainOnlyAlphanumeric)
          } else {
            shouldSucceed(Wrapper(Environment(name -> "x")))
          }
        }
      }

      behave like failsWhenExpected("(strict)", true)
      behave like failsWhenExpected("(non-strict)", false)
    }
  }

  class WithoutSecrets(strictNameValidation: Boolean = true) {
    implicit lazy val envVarValidation: Validator[Map[String, EnvVarValueOrSecret]] =
      EnvVarValidation.envValidator(strictNameValidation, Map.empty, Set.empty)

    case class Wrapper(env: Map[String, EnvVarValueOrSecret])

    object Wrapper {
      implicit val wrapperValidation: Validator[Wrapper] = validator { wrapper =>
        wrapper.env is envVarValidation // invoked here the same way that it is for apps and pods
      }
    }
  }
}
