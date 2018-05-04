/*
 * Copyright French Prime minister Office/SGMAP/DINSIC/Vitam Program (2015-2019) <p> contact.vitam@culture.gouv.fr <p>
 * This software is a computer program whose purpose is to implement a digital archiving back-office system managing
 * high volumetry securely and efficiently. <p> This software is governed by the CeCILL 2.1 license under French law and
 * abiding by the rules of distribution of free software. You can use, modify and/ or redistribute the software under
 * the terms of the CeCILL 2.1 license as circulated by CEA, CNRS and INRIA at the following URL
 * "http://www.cecill.info". <p> As a counterpart to the access to the source code and rights to copy, modify and
 * redistribute granted by the license, users are provided only with a limited warranty and the software's author, the
 * holder of the economic rights, and the successive licensors have only limited liability. <p> In this respect, the
 * user's attention is drawn to the risks associated with loading, using, modifying and/or developing or reproducing the
 * software by the user in light of its specific status of free software, that may mean that it is complicated to
 * manipulate, and that also therefore means that it is reserved for developers and experienced professionals having
 * in-depth computer knowledge. Users are therefore encouraged to load and test the software's suitability as regards
 * their requirements in conditions enabling the security of their systems and/or data to be ensured and, more
 * generally, to use and operate it in the same conditions as regards security. <p> The fact that you are presently
 * reading this means that you have had knowledge of the CeCILL 2.1 license and that you accept its terms.
 */

package fr.gouv.vitam.functional.administration.archiveunitprofiles.api.impl;

import static fr.gouv.vitam.common.database.builder.query.QueryHelper.eq;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;

import fr.gouv.vitam.common.ParametersChecker;
import fr.gouv.vitam.common.database.builder.query.VitamFieldsHelper;
import fr.gouv.vitam.common.database.builder.request.configuration.BuilderToken;
import fr.gouv.vitam.common.database.builder.request.exception.InvalidCreateOperationException;
import fr.gouv.vitam.common.database.builder.request.single.Select;
import fr.gouv.vitam.common.database.parser.request.adapter.SingleVarNameAdapter;
import fr.gouv.vitam.common.database.parser.request.single.SelectParserSingle;
import fr.gouv.vitam.common.database.server.DbRequestResult;
import fr.gouv.vitam.common.error.VitamCode;
import fr.gouv.vitam.common.error.VitamError;
import fr.gouv.vitam.common.exception.BadRequestException;
import fr.gouv.vitam.common.exception.InvalidParseOperationException;
import fr.gouv.vitam.common.exception.SchemaValidationException;
import fr.gouv.vitam.common.exception.VitamException;
import fr.gouv.vitam.common.guid.GUID;
import fr.gouv.vitam.common.guid.GUIDFactory;
import fr.gouv.vitam.common.guid.GUIDReader;
import fr.gouv.vitam.common.json.JsonHandler;
import fr.gouv.vitam.common.logging.VitamLogger;
import fr.gouv.vitam.common.logging.VitamLoggerFactory;
import fr.gouv.vitam.common.model.RequestResponse;
import fr.gouv.vitam.common.model.RequestResponseOK;
import fr.gouv.vitam.common.model.StatusCode;
import fr.gouv.vitam.common.model.administration.ArchiveUnitProfileModel;
import fr.gouv.vitam.common.model.administration.ArchiveUnitProfileStatus;
import fr.gouv.vitam.common.parameter.ParameterHelper;
import fr.gouv.vitam.common.security.SanityChecker;
import fr.gouv.vitam.common.thread.VitamThreadUtils;
import fr.gouv.vitam.functional.administration.archiveunitprofiles.api.ArchiveUnitProfileService;
import fr.gouv.vitam.functional.administration.archiveunitprofiles.core.ArchiveUnitProfileManager;
import fr.gouv.vitam.functional.administration.archiveunitprofiles.core.ArchiveUnitProfileValidator;
import fr.gouv.vitam.functional.administration.archiveunitprofiles.core.ArchiveUnitProfileValidator.RejectionCause;
import fr.gouv.vitam.functional.administration.common.ArchiveUnitProfile;
import fr.gouv.vitam.functional.administration.common.FunctionalBackupService;
import fr.gouv.vitam.functional.administration.common.VitamErrorUtils;
import fr.gouv.vitam.functional.administration.common.counter.SequenceType;
import fr.gouv.vitam.functional.administration.common.counter.VitamCounterService;
import fr.gouv.vitam.functional.administration.common.exception.ReferentialException;
import fr.gouv.vitam.functional.administration.common.server.FunctionalAdminCollections;
import fr.gouv.vitam.functional.administration.common.server.MongoDbAccessAdminImpl;
import fr.gouv.vitam.logbook.operations.client.LogbookOperationsClient;
import fr.gouv.vitam.logbook.operations.client.LogbookOperationsClientFactory;
import fr.gouv.vitam.metadata.client.MetaDataClient;
import fr.gouv.vitam.metadata.client.MetaDataClientFactory;

/**
 * The implementation of the archive unit profile CRUD
 */
public class ArchiveUnitProfileServiceImpl implements ArchiveUnitProfileService {

    private static final VitamLogger LOGGER = VitamLoggerFactory.getInstance(ArchiveUnitProfileServiceImpl.class);

    private static final String ARCHIVE_UNIT_PROFILE_IMPORT_EVENT = "IMPORT_ARCHIVEUNITPROFILE";
    private static final String ARCHIVE_UNIT_PROFILE_BACKUP_EVENT = "BACKUP_ARCHIVEUNITPROFILE";
    private static final String ARCHIVE_UNIT_PROFILES_UPDATE_EVENT = "UPDATE_ARCHIVEUNITPROFILE";

    private static final String UPDATED_DIFFS = "updatedDiffs";

    private static final String ARCHIVE_UNIT_PROFILE_IS_MANDATORY_PARAMETER = "archive unit profiles parameter is mandatory";
    private static final String ARCHIVE_UNIT_PROFILE_SERVICE_ERROR = "Archive Unit Profile service Error";
    private static final String ARCHIVE_UNIT_PROFILE_NOT_FOUND = "Update a not found archive unit profile";

    private static final String ARCHIVE_UNIT_PROFILE_STATUS_MUST_BE_ACTIVE_OR_INACTIVE =
        "The archive unit profile status must be ACTIVE or INACTIVE but not ";
    public static final String ARCHIVE_UNIT_PROFILE_IDENTIFIER_ALREADY_EXISTS_IN_DATABASE =
        "Archive unit profile identifier already exists in database ";
    public static final String ARCHIVE_UNIT_PROFILE_IDENTIFIER_MUST_BE_STRING =
        "Archive unit profile identifier must be a string ";

    private final MongoDbAccessAdminImpl mongoAccess;
    private final LogbookOperationsClient logbookClient;
    private final MetaDataClient metaDataClient;
    private final VitamCounterService vitamCounterService;
    private final FunctionalBackupService functionalBackupService;
    private static final String _TENANT = "_tenant";
    private static final String _ID = "_id";

    /**
     * Constructor
     *
     * @param mongoAccess             MongoDB client
     * @param vitamCounterService     the vitam counter service
     * @param functionalBackupService the functional backup service
     */
    public ArchiveUnitProfileServiceImpl(MongoDbAccessAdminImpl mongoAccess,
        VitamCounterService vitamCounterService, FunctionalBackupService functionalBackupService) {
        this.mongoAccess = mongoAccess;
        this.vitamCounterService = vitamCounterService;
        logbookClient = LogbookOperationsClientFactory.getInstance().getClient();
        metaDataClient=MetaDataClientFactory.getInstance().getClient();
        this.functionalBackupService = functionalBackupService;
    }


    @Override
    public RequestResponse<ArchiveUnitProfileModel> createArchiveUnitProfiles(List<ArchiveUnitProfileModel> profileModelList)
        throws VitamException {
        ParametersChecker.checkParameter(ARCHIVE_UNIT_PROFILE_IS_MANDATORY_PARAMETER, profileModelList);

        if (profileModelList.isEmpty()) {
            return new RequestResponseOK<>();
        }


        String operationId = VitamThreadUtils.getVitamSession().getRequestId();
        GUID eip = GUIDReader.getGUID(operationId);


        boolean slaveMode = vitamCounterService
            .isSlaveFunctionnalCollectionOnTenant(SequenceType.PROFILE_SEQUENCE.getCollection(),
                ParameterHelper.getTenantParameter());

        ArchiveUnitProfileManager manager = new ArchiveUnitProfileManager(logbookClient, metaDataClient,eip);
        manager.logStarted(ARCHIVE_UNIT_PROFILE_IMPORT_EVENT, null);

        final Set<String> profileIdentifiers = new HashSet<>();
        final Set<String> profileNames = new HashSet<>();
        ArrayNode archiveProfilesToPersist;

        final VitamError error =
            getVitamError(VitamCode.ARCHIVE_UNIT_PROFILE_FILE_IMPORT_ERROR.getItem(), "Global create archive unit profile error", StatusCode.KO).setHttpCode(
                Response.Status.BAD_REQUEST.getStatusCode());

        try {

            for (final ArchiveUnitProfileModel aupm : profileModelList) {

                // if a profile have and id
                if (null != aupm.getId()) {
                    error.addToErrors(getVitamError(VitamCode.ARCHIVE_UNIT_PROFILE_VALIDATION_ERROR.getItem(),
                        RejectionCause.rejectIdNotAllowedInCreate(aupm.getName()).getReason(), StatusCode.KO));
                    continue;
                }

                // if a profile with the same identifier is already treated mark the current one as duplicated
                if (ParametersChecker.isNotEmpty(aupm.getIdentifier())) {
                    if (profileIdentifiers.contains(aupm.getIdentifier())) {
                        error.addToErrors(
                            getVitamError(VitamCode.ARCHIVE_UNIT_PROFILE_VALIDATION_ERROR.getItem(), "Duplicate archive unit profiles (ID)", StatusCode.KO)
                            .setMessage(
                                "Archive unit profile identifier " + aupm.getIdentifier() + " already exists in the json"));
                        continue;
                    } else {
                        profileIdentifiers.add(aupm.getIdentifier());
                    }
                    if (profileNames.contains(aupm.getName())) {
                        error.addToErrors(
                            getVitamError(VitamCode.ARCHIVE_UNIT_PROFILE_VALIDATION_ERROR.getItem(), "Duplicate archive unit profiles (Names)", StatusCode.KO)
                            .setMessage(
                                "Archive unit profile name " + aupm.getName() + " already exists in the json"));
                        continue;
                    } else {
                        profileNames.add(aupm.getName());
                    }
                }

                // validate profile
                if (manager.validateArchiveUnitProfile(aupm, error)) {
                    aupm.setId(GUIDFactory.newProfileGUID(ParameterHelper.getTenantParameter()).getId());
                }
                if (aupm.getTenant() == null) {
                    aupm.setTenant(ParameterHelper.getTenantParameter());
                }

                //Json schema validation of profile ControlSchema property
                final Optional<ArchiveUnitProfileValidator.RejectionCause> checkSchema =
                    manager.createJsonSchemaValidator().validate(aupm);
                checkSchema.ifPresent(t -> error
                    .addToErrors(
                        new VitamError(VitamCode.ARCHIVE_UNIT_PROFILE_VALIDATION_ERROR.getItem())
                            .setDescription(checkSchema.get().getReason())
                            .setMessage(ArchiveUnitProfileManager.INVALID_JSON_SCHEMA)
                    )
                );

                final Optional<ArchiveUnitProfileValidator.RejectionCause> resultName =
                    manager.createCheckDuplicateNamesInDatabaseValidator().validate(aupm);
                resultName.ifPresent(t -> error
                    .addToErrors(
                        new VitamError(VitamCode.ARCHIVE_UNIT_PROFILE_VALIDATION_ERROR.getItem())
                            .setDescription(resultName.get().getReason())
                            .setMessage(ArchiveUnitProfileManager.DUPLICATE_IN_DATABASE)
                    )
                );
                
                if (slaveMode) {
                    final Optional<ArchiveUnitProfileValidator.RejectionCause> result =
                        manager.checkEmptyIdentifierSlaveModeValidator().validate(aupm);
                    result.ifPresent(t -> error
                        .addToErrors(
                            new VitamError(VitamCode.ARCHIVE_UNIT_PROFILE_VALIDATION_ERROR.getItem())
                                .setMessage(ArchiveUnitProfileManager.DUPLICATE_IN_DATABASE)
                                .setDescription(result.get().getReason())
                                .setState(StatusCode.KO.name())
                        )
                    );
                }
            }

            if (null != error.getErrors() && !error.getErrors().isEmpty()) {
                // log book + application log
                // stop
                final String errorsDetails =
                    error.getErrors().stream().map(c -> c.getDescription())
                    .collect(Collectors.joining(","));
                manager.logValidationError(ARCHIVE_UNIT_PROFILE_IMPORT_EVENT, null, errorsDetails,
                        error.getErrors().get(0).getMessage());
                return error;
            }

            archiveProfilesToPersist = JsonHandler.createArrayNode();
            for (final ArchiveUnitProfileModel aupm : profileModelList) {
                setIdentifier(slaveMode, aupm);

                final ObjectNode archiveProfileNode = (ObjectNode) JsonHandler.toJsonNode(aupm);
                JsonNode jsonNode = archiveProfileNode.remove(VitamFieldsHelper.id());
                /* contract is valid, add it to the list to persist */

                if (jsonNode != null) {
                    archiveProfileNode.set(_ID, jsonNode);
                }
                JsonNode hashTenant = archiveProfileNode.remove(VitamFieldsHelper.tenant());
                if (hashTenant != null) {
                    archiveProfileNode.set(_TENANT, hashTenant);
                }
                archiveProfilesToPersist.add(archiveProfileNode);
            }
            // at this point no exception occurred and no validation error detected
            // persist in collection
            // TODO: 3/28/17 create insertDocuments method that accepts VitamDocument instead of ArrayNode, so we can
            // use ArchiveUnitProfile at this point
            mongoAccess.insertDocuments(archiveProfilesToPersist, FunctionalAdminCollections.ARCHIVE_UNIT_PROFILE).close();

            functionalBackupService.saveCollectionAndSequence(
                eip,
                ARCHIVE_UNIT_PROFILE_BACKUP_EVENT,
                FunctionalAdminCollections.ARCHIVE_UNIT_PROFILE,
                eip.toString()
                );
        } catch(SchemaValidationException e) {
            LOGGER.error(e);
            final String err = "Import archive unit profile error > " + e.getMessage();

            // logbook error event 
            manager.logValidationError(ARCHIVE_UNIT_PROFILE_IMPORT_EVENT, null, err,
                    ArchiveUnitProfileManager.IMPORT_KO);

            return getVitamError(VitamCode.ARCHIVE_UNIT_PROFILE_VALIDATION_ERROR.getItem(), e.getMessage(),
                StatusCode.KO).setHttpCode(Response.Status.BAD_REQUEST.getStatusCode());
        } catch (final Exception e) {
            final String err = new StringBuilder("Import archive unit profiles error : ").append(e.getMessage()).toString();
            manager.logFatalError(ARCHIVE_UNIT_PROFILE_IMPORT_EVENT, null, err);
            return getVitamError(VitamCode.ARCHIVE_UNIT_PROFILE_FILE_IMPORT_ERROR.getItem(), err, StatusCode.KO).setHttpCode(
                Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        }

        manager.logSuccess(ARCHIVE_UNIT_PROFILE_IMPORT_EVENT, null, null);

        return new RequestResponseOK<ArchiveUnitProfileModel>().addAllResults(profileModelList)
            .setHttpCode(Response.Status.CREATED.getStatusCode());
    }

    @Override
    public RequestResponseOK<ArchiveUnitProfileModel> findArchiveUnitProfiles(JsonNode queryDsl)
        throws ReferentialException, InvalidParseOperationException {
        try (DbRequestResult result =
            mongoAccess.findDocuments(queryDsl, FunctionalAdminCollections.ARCHIVE_UNIT_PROFILE)) {
            return result.getRequestResponseOK(queryDsl, ArchiveUnitProfile.class, ArchiveUnitProfileModel.class);
        }
    }

    @Override
    public RequestResponse updateArchiveUnitProfile(String id, JsonNode queryDsl) throws VitamException {
        final ArchiveUnitProfileModel profileModel = findByIdentifier(id);
        if (profileModel == null) {
            return getVitamError(VitamCode.ARCHIVE_UNIT_PROFILE_VALIDATION_ERROR.getItem(),
                ARCHIVE_UNIT_PROFILE_NOT_FOUND, StatusCode.KO)
                    .setHttpCode(Response.Status.NOT_FOUND.getStatusCode())
                    .setMessage(ArchiveUnitProfileManager.UPDATE_AUP_NOT_FOUND);
        }


        String operationId = VitamThreadUtils.getVitamSession().getRequestId();
        GUID eip = GUIDReader.getGUID(operationId);
        final ArchiveUnitProfileManager manager = new ArchiveUnitProfileManager(logbookClient, metaDataClient,eip);
        Map<String, List<String>> updateDiffs;
        manager.logStarted(ARCHIVE_UNIT_PROFILES_UPDATE_EVENT, profileModel.getId());

        if (queryDsl == null || !queryDsl.isObject()) {
            manager.logValidationError(ARCHIVE_UNIT_PROFILES_UPDATE_EVENT, profileModel.getId(),
                "Update query dsl must be an object and not null", ArchiveUnitProfileManager.UPDATE_KO);
            return getVitamError(VitamCode.ARCHIVE_UNIT_PROFILE_VALIDATION_ERROR.getItem(),
                "Update query dsl must be an object and not null : " + profileModel.getIdentifier(), StatusCode.KO)
                    .setHttpCode(Response.Status.BAD_REQUEST.getStatusCode())
                    .setMessage(ArchiveUnitProfileManager.UPDATE_KO);
        }

        final VitamError error = getVitamError(VitamCode.ARCHIVE_UNIT_PROFILE_VALIDATION_ERROR.getItem(),
            "Update archive unit profile error : " + profileModel.getIdentifier(), StatusCode.KO)
            .setHttpCode(
                Response.Status.BAD_REQUEST.getStatusCode());

        final JsonNode actionNode = queryDsl.get(BuilderToken.GLOBAL.ACTION.exactToken());

        for (final JsonNode fieldToSet : actionNode) {
            final JsonNode fieldName = fieldToSet.get(BuilderToken.UPDATEACTION.SET.exactToken());
            if (fieldName != null) {
                final Iterator<String> it = fieldName.fieldNames();
                while (it.hasNext()) {
                    final String field = it.next();
                    final JsonNode value = fieldName.findValue(field);
                    validateUpdateAction(profileModel, error, field, value, manager);
                }
            }
        }

        if (error.getErrors() != null && error.getErrors().size() > 0) {
            final String errorsDetails =
                error.getErrors().stream().map(c -> c.getDescription()).collect(Collectors.joining(","));
            manager.logValidationError(ARCHIVE_UNIT_PROFILES_UPDATE_EVENT, profileModel.getId(), errorsDetails,
                    error.getErrors().get(0).getMessage());

            return error;
        }

        String wellFormedJson = null;
        try {
            try (DbRequestResult result = mongoAccess.updateData(queryDsl, FunctionalAdminCollections.ARCHIVE_UNIT_PROFILE)) {
                updateDiffs = result.getDiffs();
            } catch (SchemaValidationException | BadRequestException e) {
                LOGGER.error(e);
                final String err = "Update archive unit profile error > " + e.getMessage();

                // logbook error event 
                manager.logValidationError(ARCHIVE_UNIT_PROFILES_UPDATE_EVENT, profileModel.getId(), err,
                        ArchiveUnitProfileManager.UPDATE_KO);

                return getVitamError(VitamCode.ARCHIVE_UNIT_PROFILE_VALIDATION_ERROR.getItem(), e.getMessage(),
                    StatusCode.KO).setHttpCode(Response.Status.BAD_REQUEST.getStatusCode());
            }

            List<String> diff = updateDiffs.get(profileModel.getId());
            try {
                final ObjectNode object = JsonHandler.createObjectNode();
                object.put(UPDATED_DIFFS, Joiner.on(" ").join(diff));
                wellFormedJson = SanityChecker.sanitizeJson(object);
            } catch (InvalidParseOperationException e) {
                // Do nothing
                LOGGER.info("Invalid parse Exception while sanitize updated diffs");
            }

            functionalBackupService.saveCollectionAndSequence(
                eip,
                ARCHIVE_UNIT_PROFILE_BACKUP_EVENT,
                FunctionalAdminCollections.ARCHIVE_UNIT_PROFILE,
                profileModel.getId()
                );

        } catch (final Exception e) {
            LOGGER.error(e);
            final String err = new StringBuilder("Update archive unit profile error : ").append(e.getMessage()).toString();
            manager.logFatalError(ARCHIVE_UNIT_PROFILES_UPDATE_EVENT, profileModel.getId(), err);
            error.setCode(VitamCode.GLOBAL_INTERNAL_SERVER_ERROR.getItem())
            .setDescription(err)
            .setHttpCode(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());

            return error;
        }

        manager.logSuccess(ARCHIVE_UNIT_PROFILES_UPDATE_EVENT, profileModel.getId(), wellFormedJson);
        return new RequestResponseOK().setHttpCode(Response.Status.OK.getStatusCode());
    }

    @Override
    public void close() {
        if (null != logbookClient) {
            logbookClient.close();
        }
    }

    @VisibleForTesting
    public ArchiveUnitProfileModel findByIdentifier(String id)
        throws ReferentialException, InvalidParseOperationException {
        SanityChecker.checkParameter(id);
        final SelectParserSingle parser = new SelectParserSingle(new SingleVarNameAdapter());
        parser.parse(new Select().getFinalSelect());
        try {
            parser.addCondition(eq(ArchiveUnitProfile.IDENTIFIER, id));
        } catch (final InvalidCreateOperationException e) {
            throw new ReferentialException(e);
        }

        try (DbRequestResult result =
            mongoAccess.findDocuments(parser.getRequest().getFinalSelect(), FunctionalAdminCollections.ARCHIVE_UNIT_PROFILE)) {
            final List<ArchiveUnitProfileModel> list = result.getDocuments(ArchiveUnitProfile.class, ArchiveUnitProfileModel.class);
            if (list.isEmpty()) {
                return null;
            }
            return list.get(0);
        }
    }

    /**
     * Validate dsl action
     *
     * @param profileModel
     * @param error thrown errors
     * @param field the field to check
     * @param value the calue to check
     */
    private void validateUpdateAction(ArchiveUnitProfileModel profileModel, final VitamError error, final String field,
        final JsonNode value, ArchiveUnitProfileManager manager) {
        if (ArchiveUnitProfile.STATUS.equals(field)) {
            if (!(ArchiveUnitProfileStatus.ACTIVE.name().equals(value.asText())
                || ArchiveUnitProfileStatus.INACTIVE.name().equals(value.asText()))) {
                error.addToErrors(getVitamError(VitamCode.ARCHIVE_UNIT_PROFILE_VALIDATION_ERROR.getItem(),
                    ARCHIVE_UNIT_PROFILE_STATUS_MUST_BE_ACTIVE_OR_INACTIVE + value.asText(), StatusCode.KO)
                        .setHttpCode(Response.Status.BAD_REQUEST.getStatusCode())
                        .setMessage(ArchiveUnitProfileManager.UPDATE_VALUE_NOT_IN_ENUM)
                );
            }
        }

        if (ArchiveUnitProfileModel.CONTROLSCHEMA.equals(field)) {
            final Optional<ArchiveUnitProfileValidator.RejectionCause> checkSchema =
                manager.createJsonSchemaValidator().validate(new ArchiveUnitProfileModel().setControlSchema(value.asText()));
            checkSchema.ifPresent(t -> error
                .addToErrors(new VitamError(VitamCode.ARCHIVE_UNIT_PROFILE_VALIDATION_ERROR.getItem())
                        .setDescription(checkSchema.get().getReason())
                        .setMessage(ArchiveUnitProfileManager.UPDATE_KO)
                )
            );
        }
        
        if (ArchiveUnitProfileModel.TAG_IDENTIFIER.equals(field)) {
            if (!value.isTextual()) {
                error.addToErrors(getVitamError(VitamCode.ARCHIVE_UNIT_PROFILE_VALIDATION_ERROR.getItem(),
                    ARCHIVE_UNIT_PROFILE_IDENTIFIER_MUST_BE_STRING + " : " + value.asText(), StatusCode.KO)
                        .setHttpCode(Response.Status.BAD_REQUEST.getStatusCode())
                        .setMessage(ArchiveUnitProfileManager.UPDATE_KO)
                );
            } else if (!profileModel.getIdentifier().equals(value.asText())) {
                Optional<RejectionCause> validateIdentifier = manager.createCheckDuplicateInDatabaseValidator()
                    .validate(new ArchiveUnitProfileModel().setIdentifier(value.asText()));
                if (validateIdentifier.isPresent()) {
                    error.addToErrors(getVitamError(VitamCode.ARCHIVE_UNIT_PROFILE_VALIDATION_ERROR.getItem(),
                        ARCHIVE_UNIT_PROFILE_IDENTIFIER_ALREADY_EXISTS_IN_DATABASE + " : " + value.asText(), StatusCode.KO)
                            .setHttpCode(Response.Status.BAD_REQUEST.getStatusCode())
                            .setMessage(ArchiveUnitProfileManager.UPDATE_DUPLICATE_IN_DATABASE)
                    );
                }
            }
        }


        if (ArchiveUnitProfileModel.CONTROLSCHEMA.equals(field)) {
            //Json schema validation of profileModel ControlSchema property
            final Optional<ArchiveUnitProfileValidator.RejectionCause> checkSchema =
                manager.createJsonSchemaValidator().validate(profileModel);
            checkSchema.ifPresent(t -> error
                .addToErrors(new VitamError(VitamCode.ARCHIVE_UNIT_PROFILE_VALIDATION_ERROR.getItem())
                        .setDescription(checkSchema.get().getReason())
                        .setMessage(ArchiveUnitProfileManager.UPDATE_KO)
                )
            );

            //check if the archiveUnitProfile is used by an archiveUnit
            final Optional<ArchiveUnitProfileValidator.RejectionCause> checkUsedJsonSchema =
                manager.createCheckUsedJsonSchema().validate(profileModel);
            checkUsedJsonSchema.ifPresent(t -> error
                .addToErrors(new VitamError(VitamCode.ARCHIVE_UNIT_PROFILE_VALIDATION_ERROR.getItem())
                        .setDescription(checkUsedJsonSchema.get().getReason())
                        .setMessage(ArchiveUnitProfileManager.UPDATE_KO)
                )
            );
        }


    }

    private void setIdentifier(boolean slaveMode, ArchiveUnitProfileModel archiveUnitProfilModel)
        throws ReferentialException {
        if (!slaveMode) {
            String code = vitamCounterService.getNextSequenceAsString(ParameterHelper.getTenantParameter(),
                SequenceType.ARCHIVE_UNIT_PROFILE_SEQUENCE);
            archiveUnitProfilModel.setIdentifier(code);
        }
    }

    private VitamError getVitamError(String vitamCode, String error, StatusCode statusCode) {
        return VitamErrorUtils.getVitamError(vitamCode, error, "ArchiveUnitProfile", statusCode);
    }
}