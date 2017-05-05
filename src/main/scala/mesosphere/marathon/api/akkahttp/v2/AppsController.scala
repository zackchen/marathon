package mesosphere.marathon
package api.akkahttp
package v2

import akka.event.EventStream
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ StatusCodes, Uri }
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.{ Directive1, Rejection, RejectionError, Route }
import akka.stream.Materializer
import mesosphere.marathon.api.v2.{ AppNormalization, AppTasksResource, InfoEmbedResolver, LabelSelectorParsers, AppHelpers }
import mesosphere.marathon.api.akkahttp.{ Controller, EntityMarshallers }
import mesosphere.marathon.api.v2.AppHelpers.authzSelector
import mesosphere.marathon.api.v2.validation.AppValidation
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.event.ApiPostEvent
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.plugin.auth.{ Authenticator => MarathonAuthenticator, Authorizer, CreateRunSpec, DeleteRunSpec, Identity, UpdateRunSpec, ViewResource, ViewRunSpec }
import mesosphere.marathon.state.{ AppDefinition, Identifiable, PathId, RootGroup, Timestamp }
import mesosphere.marathon.stream.Sink
import mesosphere.marathon.util.RichEither
import play.api.libs.json._
import mesosphere.marathon.core.election.ElectionService

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }
import scala.util.control.NonFatal

class AppsController(
    val clock: Clock,
    val eventBus: EventStream,
    val appTasksRes: AppTasksResource,
    val service: MarathonSchedulerService,
    val appInfoService: AppInfoService,
    val config: MarathonConf,
    val groupManager: GroupManager,
    val pluginManager: PluginManager)(
    implicit
    val actorSystem: ActorSystem,
    val materializer: Materializer,
    val executionContext: ExecutionContext,
    val authenticator: MarathonAuthenticator,
    val authorizer: Authorizer,
    val electionService: ElectionService
) extends Controller {
  import Directives._
  import RichEither.RightBiased

  private implicit lazy val validateApp = AppDefinition.validAppDefinition(config.availableFeatures)(pluginManager)
  private implicit lazy val updateValidator = AppValidation.validateCanonicalAppUpdateAPI(config.availableFeatures)

  import AppHelpers._
  import EntityMarshallers._

  import mesosphere.marathon.api.v2.json.Formats._

  private val forceParameter =
    (parameter('force.as[Boolean].?(false)))

  private def listApps(implicit identity: Identity): Route = {
    parameters('cmd.?, 'id.?, 'label.?, 'embed.*) { (cmd, id, label, embed) =>
      def index: Future[Seq[AppInfo]] = {
        def containCaseInsensitive(a: String, b: String): Boolean = b.toLowerCase contains a.toLowerCase

        val selectors = Seq[Option[Selector[AppDefinition]]](
          cmd.map(c => Selector(_.cmd.exists(containCaseInsensitive(c, _)))),
          id.map(s => Selector(app => containCaseInsensitive(s, app.id.toString))),
          label.map(new LabelSelectorParsers().parsed),
          Some(authzSelector)
        ).flatten
        val resolvedEmbed = InfoEmbedResolver.resolveApp(embed.toSet) + AppInfo.Embed.Counts + AppInfo.Embed.Deployments
        appInfoService.selectAppsBy(Selector.forall(selectors), resolvedEmbed)
      }
      onSuccess(index)(apps => complete(Json.obj("apps" -> apps)))
    }
  }

  private def createApp(implicit identity: Identity): Route = {
    (entity(as[AppDefinition]) & forceParameter) { (app, force) =>

      def create: Future[(DeploymentPlan, AppInfo)] = {

        def createOrThrow(opt: Option[AppDefinition]) = opt
          .map(_ => throw ConflictingChangeException(s"An app with id [${app.id}] already exists."))
          .getOrElse(app)

        groupManager.updateApp(app.id, createOrThrow, app.version, force).map { plan =>
          val appWithDeployments = AppInfo(
            app,
            maybeCounts = Some(TaskCounts.zero),
            maybeTasks = Some(Seq.empty),
            maybeDeployments = Some(Seq(Identifiable(plan.id)))
          )
          plan -> appWithDeployments
        }
      }
      authorized(CreateRunSpec, app).apply {
        onSuccess(create) { (plan, app) =>
          //TODO: post ApiPostEvent
          complete((StatusCodes.Created, Seq(Headers.`Marathon-Deployment-Id`(plan.id)), app))
        }
      }
    }
  }

  private def showApp(appId: PathId)(implicit identity: Identity): Route = {
    parameters('embed.*) { embed =>
      val resolvedEmbed = InfoEmbedResolver.resolveApp(embed.toSet) ++ Set(
        // deprecated. For compatibility.
        AppInfo.Embed.Counts, AppInfo.Embed.Tasks, AppInfo.Embed.LastTaskFailure, AppInfo.Embed.Deployments
      )

      onSuccess(appInfoService.selectApp(appId, authzSelector, resolvedEmbed)) {
        case None =>
          reject(Rejections.EntityNotFound.app(appId))
        case Some(info) =>
          authorized(ViewResource, info.app, Rejections.EntityNotFound.app(appId)).apply {
            complete(Json.obj("app" -> info))
          }
      }
    }
  }

  /**
    * Internal representation of `replace or update` logic.
    *
    * @param id appId
    * @param body request body
    * @param force force update?
    * @param partialUpdate partial update?
    * @param req http servlet request
    * @param allowCreation is creation allowed?
    * @param identity implicit identity
    * @return http servlet response
    */
  private[this] def update(appId: PathId, partialUpdate: Boolean, allowCreation: Boolean)(implicit identity: Identity) = {
    val version = clock.now()

    (forceParameter &
      extractClientIP &
      extractUri &
      entity(as(appUpdateUnmarshaller(appId, partialUpdate)))) { (force, remoteAddr, requestUri, appUpdate) =>
        /* Note - this function throws exceptions and handles authorization synchronously. We need to catch and map these
       * exceptions to the appropriate rejections */
        val fn = updateOrCreate(
          appId, _: Option[AppDefinition], appUpdate, partialUpdate, allowCreation, clock.now(), service)

        onSuccessLegacy(appId)(groupManager.updateApp(appId, fn, version, force)).apply { plan =>
          plan.target.app(appId).foreach { appDef =>
            eventBus.publish(ApiPostEvent(remoteAddr.toString, requestUri.toString, appDef))
          }

          completeWithDeploymentForApp(appId, plan)
        }
      }
  }

  private def completeWithDeploymentForApp(appId: PathId, plan: DeploymentPlan) =
    plan.original.app(appId) match {
      case Some(_) =>
        complete(Messages.DeploymentResult(plan))
      case None =>
        complete((StatusCodes.Created, List(Location(Uri(appId.toString))), Messages.DeploymentResult(plan)))
    }

  /**
    * It'd be neat if we didn't need this. Would take some heavy-ish refactoring to get all of the update functions to
    * take an either.
    */
  private def onSuccessLegacy[T](appId: PathId)(f: => Future[T])(implicit identity: Identity): Directive1[T] = onComplete({
    try { f }
    catch {
      case NonFatal(ex) =>
        Future.failed(ex)
    }
  }).flatMap {
    case Success(t) =>
      provide(t)
    case Failure(ValidationFailedException(_, failure)) =>
      reject(EntityMarshallers.ValidationFailed(failure))
    case Failure(AccessDeniedException(msg)) =>
      reject(AuthDirectives.NotAuthorized(HttpPluginFacade.response(authorizer.handleNotAuthorized(identity, _))))
    case Failure(_: AppNotFoundException) =>
      reject(Rejections.EntityNotFound.app(appId))
    case Failure(ex) =>
      throw ex
  }

  private def patchSingle(appId: PathId)(implicit identity: Identity) =
    update(appId, partialUpdate = true, allowCreation = false)

  private def putSingle(appId: PathId)(implicit identity: Identity) =
    parameter('partialUpdate.as[Boolean].?(true)) { partialUpdate =>
      update(appId, partialUpdate = partialUpdate, allowCreation = true)
    }

  private def deleteSingle(appId: PathId)(implicit identity: Identity) =
    forceParameter { force =>
      lazy val notFound: Either[Rejection, RootGroup] =
        Left(Rejections.EntityNotFound.app(appId))

      def deleteApp(rootGroup: RootGroup): Either[Rejection, RootGroup] = {
        rootGroup.app(appId) match {
          case None =>
            notFound
          case Some(app) =>
            if (authorizer.isAuthorized(identity, DeleteRunSpec, app))
              Right(rootGroup.removeApp(appId))
            else
              notFound
        }
      }

      rejectingLeft(onSuccess(groupManager.updateRootEither(appId.parent, deleteApp, force = force))) { plan =>
        completeWithDeploymentForApp(appId, plan)
      }
    }

  private def rejectLeftViaThrow[T](t: Either[Rejection, T]): T = t match {
    case Left(r) => throw new RejectionError(r)
    case Right(t) => t
  }

  private def restartApp(appId: PathId)(implicit identity: Identity) = {
    forceParameter { force =>

      def markForRestartingOrThrow(opt: Option[AppDefinition]): Either[Rejection, AppDefinition] =
        opt.map(Right(_)).getOrElse(Left(Rejections.EntityNotFound.app(appId): Rejection))
          .flatMap { checkAuthorization(UpdateRunSpec, _) }
          .map(_.markedForRestarting)

      val newVersion = clock.now()

      onSuccess(
        groupManager.updateApp(
          appId,
          { app => rejectLeftViaThrow(markForRestartingOrThrow(app)) },
          newVersion, force)
      ) { restartDeployment =>
          completeWithDeploymentForApp(appId, restartDeployment)
        }
    }
  }

  private def listVersions(appId: PathId)(implicit identity: Identity) = {
    val versions = groupManager.appVersions(appId).runWith(Sink.seq)
    authorized(ViewRunSpec, groupManager.app(appId), Rejections.EntityNotFound.app(appId)).apply {
      onSuccess(versions) { versions =>
        complete(Json.obj("versions" -> versions))
      }
    }
  }

  private def getVersion(appId: PathId, version: Timestamp)(implicit identity: Identity) = {
    onSuccess(groupManager.appVersion(appId, version.toOffsetDateTime)) {
      case Some(app) =>
        authorized(ViewRunSpec, app, Rejections.EntityNotFound.app(appId)).apply {
          complete(app)
        }
      case None =>
        reject(Rejections.EntityNotFound.app(appId))
    }
  }

  // format: OFF
  val route: Route = {
    asLeader(electionService) {
      authenticated.apply { implicit identity =>
        pathEnd {
          post {
            createApp
          } ~
          get {
            listApps
          }
        } ~
        pathPrefix(ExistingAppPathId(groupManager.rootGroup)) { appId =>
          pathEndOrSingleSlash {
            get {
              showApp(appId)
            } ~
            patch {
              patchSingle(appId)
            } ~
            put {
              putSingle(appId)
            } ~
            delete {
              deleteSingle(appId)
            }
          } ~
          (path("restart") & post) {
            restartApp(appId)
          } ~
          pathPrefix("tasks") {
            // TODO implement with tasks controller
            ???
          } ~
          pathPrefix("versions") {
            (pathEnd & get) {
              listVersions(appId)
            } ~
            path(Segment.map(Timestamp(_))) { version =>
              getVersion(appId, version)
            }
          }
        } ~
        pathPrefix(RemainingPathId) { appId =>
          put {
            // We allow PUT to create an app that does not exist
            putSingle(appId)
          }
        }
      }
    }
  }
  // format: ON

  private val normalizationConfig = AppNormalization.Configure(
    config.defaultNetworkName.get,
    config.mesosBridgeName())

  private implicit val validateAndNormalizeApp: Normalization[raml.App] =
    appNormalization(config.availableFeatures, normalizationConfig)(AppNormalization.withCanonizedIds())

  private implicit val validateAndNormalizeAppUpdate: Normalization[raml.AppUpdate] =
    appUpdateNormalization(config.availableFeatures, normalizationConfig)(AppNormalization.withCanonizedIds())
}
