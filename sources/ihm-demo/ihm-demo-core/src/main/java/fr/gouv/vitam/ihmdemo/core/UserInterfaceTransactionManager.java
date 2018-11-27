/*******************************************************************************
 * Copyright French Prime minister Office/SGMAP/DINSIC/Vitam Program (2015-2019)
 *
 * contact.vitam@culture.gouv.fr
 *
 * This software is a computer program whose purpose is to implement a digital archiving back-office system managing
 * high volumetry securely and efficiently.
 *
 * This software is governed by the CeCILL 2.1 license under French law and abiding by the rules of distribution of free
 * software. You can use, modify and/ or redistribute the software under the terms of the CeCILL 2.1 license as
 * circulated by CEA, CNRS and INRIA at the following URL "http://www.cecill.info".
 *
 * As a counterpart to the access to the source code and rights to copy, modify and redistribute granted by the license,
 * users are provided only with a limited warranty and the software's author, the holder of the economic rights, and the
 * successive licensors have only limited liability.
 *
 * In this respect, the user's attention is drawn to the risks associated with loading, using, modifying and/or
 * developing or reproducing the software by the user in light of its specific status of free software, that may mean
 * that it is complicated to manipulate, and that also therefore means that it is reserved for developers and
 * experienced professionals having in-depth computer knowledge. Users are therefore encouraged to load and test the
 * software's suitability as regards their requirements in conditions enabling the security of their systems and/or data
 * to be ensured and, more generally, to use and operate it in the same conditions as regards security.
 *
 * The fact that you are presently reading this means that you have had knowledge of the CeCILL 2.1 license and that you
 * accept its terms.
 *******************************************************************************/
package fr.gouv.vitam.ihmdemo.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.gouv.vitam.access.external.client.AccessExternalClient;
import fr.gouv.vitam.access.external.client.AccessExternalClientFactory;
import fr.gouv.vitam.access.external.client.AdminExternalClient;
import fr.gouv.vitam.access.external.client.AdminExternalClientFactory;
import fr.gouv.vitam.access.external.client.v2.AccessExternalClientV2Factory;
import fr.gouv.vitam.access.external.client.v2.AccessExternalClientV2;
import fr.gouv.vitam.access.external.common.exception.AccessExternalClientNotFoundException;
import fr.gouv.vitam.access.external.common.exception.AccessExternalClientServerException;
import fr.gouv.vitam.common.GlobalDataRest;
import fr.gouv.vitam.common.LocalDateUtil;
import fr.gouv.vitam.common.client.VitamContext;
import fr.gouv.vitam.common.database.builder.request.exception.InvalidCreateOperationException;
import fr.gouv.vitam.common.database.builder.request.single.Select;
import fr.gouv.vitam.common.exception.AccessUnauthorizedException;
import fr.gouv.vitam.common.exception.BadRequestException;
import fr.gouv.vitam.common.exception.InvalidParseOperationException;
import fr.gouv.vitam.common.exception.VitamClientException;
import fr.gouv.vitam.common.json.JsonHandler;
import fr.gouv.vitam.common.logging.VitamLogger;
import fr.gouv.vitam.common.logging.VitamLoggerFactory;
import fr.gouv.vitam.common.model.ProbativeValueRequest;
import fr.gouv.vitam.common.model.RequestResponse;
import fr.gouv.vitam.common.model.administration.AccessionRegisterSummaryModel;
import fr.gouv.vitam.common.model.administration.AccessionRegisterSymbolicModel;
import fr.gouv.vitam.common.model.dip.DipExportRequest;
import fr.gouv.vitam.common.model.elimination.EliminationRequestBody;
import fr.gouv.vitam.common.model.logbook.LogbookLifecycle;
import fr.gouv.vitam.common.model.logbook.LogbookOperation;
import fr.gouv.vitam.common.server.application.AsyncInputStreamHelper;
import fr.gouv.vitam.common.stream.StreamUtils;
import fr.gouv.vitam.logbook.common.exception.LogbookClientException;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cms.SignerId;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampResponse;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.Map;

import static fr.gouv.vitam.common.auth.web.filter.CertUtils.REQUEST_PERSONAL_CERTIFICATE_ATTRIBUTE;

/**
 * Manage all the transactions received form the User Interface : a gateway to VITAM intern
 */
public class UserInterfaceTransactionManager {
    private static final VitamLogger LOGGER = VitamLoggerFactory.getInstance(UserInterfaceTransactionManager.class);

    /**
     * Gets search units result
     *
     * @param parameters search criteria as DSL query
     * @param context Vitamcontext
     * @return result
     * @throws VitamClientException access client exception
     */
    public static RequestResponse<JsonNode> searchUnits(JsonNode parameters, VitamContext context)
        throws VitamClientException {
        try (AccessExternalClient client = AccessExternalClientFactory.getInstance().getClient()) {
            return client.selectUnits(context, parameters);
        }
    }

    /**
     * Gets search objects result
     *
     * @param parameters   search criteria as DSL query
     * @param context   Vitamcontext
     * @return result
     * @throws VitamClientException access client exception
     */
    public static RequestResponse<JsonNode> searchObjects(JsonNode parameters, VitamContext context)
            throws VitamClientException {
        try (AccessExternalClient client = AccessExternalClientFactory.getInstance().getClient()) {
            return client.selectObjects(context, parameters);
        }
    }

    /**
     * Gets archive unit details
     *
     * @param preparedDslQuery search criteria as DSL query
     * @param unitId archive unit id to find
     * @param context Vitamcontext
     * @return result
     * @throws VitamClientException access client exception
     */
    public static RequestResponse<JsonNode> getArchiveUnitDetails(JsonNode preparedDslQuery, String unitId,
        VitamContext context)
        throws VitamClientException {
        try (AccessExternalClient client = AccessExternalClientFactory.getInstance().getClient()) {
            return client.selectUnitbyId(
                context,
                preparedDslQuery,
                unitId);
        }
    }

    /**
     * Gets archive unit details with inheritedRules
     *
     * @param preparedDslQuery search criteria as DSL query
     * @param context Vitamcontext
     * @return result
     * @throws VitamClientException access client exception
     */
    public static RequestResponse<JsonNode> selectUnitsWithInheritedRules(JsonNode preparedDslQuery,
        VitamContext context)
        throws VitamClientException {
        try (AccessExternalClient client = AccessExternalClientFactory.getInstance().getClient()) {
            return client.selectUnitsWithInheritedRules(context, preparedDslQuery);
        }
    }

    /**
     * Start elimination analysis
     *
     * @param parameters input for elimination workflow
     * @param context Vitamcontext
     * @return result    HTTP response
     * @throws VitamClientException VitamClientException
     */
    public static RequestResponse<JsonNode> startEliminationAnalysis(EliminationRequestBody parameters,
        VitamContext context)
        throws VitamClientException {
        try (AccessExternalClient client = AccessExternalClientFactory.getInstance().getClient()) {
            return client.startEliminationAnalysis(context, parameters);
        }
    }

    /**
     * Start elimination action
     *
     * @param parameters input for elimination workflow
     * @param context Vitamcontext
     * @return result    HTTP response
     * @throws VitamClientException
     */
    public static RequestResponse<JsonNode> startEliminationAction(EliminationRequestBody parameters,
        VitamContext context)
        throws VitamClientException {
        try (AccessExternalClient client = AccessExternalClientFactory.getInstance().getClient()) {
            return client.startEliminationAction(context, parameters);
        }
    }

    /**
     * Massive AU update
     *
     * @param parameters search criteria as DSL query
     * @param context Vitamcontext
     * @return result
     * @throws VitamClientException
     */
    public static RequestResponse<JsonNode> massiveUnitsUpdate(JsonNode parameters, VitamContext context)
        throws VitamClientException {
        try (AccessExternalClient client = AccessExternalClientFactory.getInstance().getClient()) {
            return client.massUpdateUnits(context, parameters);
        }
    }

    /**
     * Massive Rules update
     *
     * @param parameters search criteria as DSL query
     * @param context Vitamcontext
     * @return result
     * @throws VitamClientException
     */
    public static RequestResponse<JsonNode> massiveRulesUpdate(JsonNode parameters, VitamContext context)
        throws VitamClientException {
        try (AccessExternalClient client = AccessExternalClientFactory.getInstance().getClient()) {
            return client.massUpdateUnitsRules(context, parameters);
        }
    }

    /**
     * Update units result
     *
     * @param parameters search criteria as DSL query
     * @param unitId unitIdentifier
     * @param context Vitamcontext
     * @return result
     * @throws VitamClientException
     */
    public static RequestResponse<JsonNode> updateUnits(JsonNode parameters, String unitId, VitamContext context)
        throws VitamClientException {
        try (AccessExternalClient client = AccessExternalClientFactory.getInstance().getClient()) {
            return client.updateUnitbyId(context, parameters, unitId);
        }
    }

    /**
     * Retrieve an ObjectGroup as Json data based on the provided ObjectGroup id
     *
     * @param preparedDslQuery the query to be executed
     * @param objectId the Id of the ObjectGroup
     * @param context Vitamcontext
     * @return JsonNode object including DSL queries, context and results
     * @throws VitamClientException if the client encountered an exception
     */
    public static RequestResponse<JsonNode> selectObjectbyId(JsonNode preparedDslQuery, String objectId,
        VitamContext context)
        throws VitamClientException {
        try (AccessExternalClient client = AccessExternalClientFactory.getInstance().getClient()) {
            return client.selectObjectMetadatasByUnitId(
                context,
                preparedDslQuery,
                objectId);
        }
    }

    /**
     * Retrieve an Object data as an input stream
     *
     * @param asyncResponse the asynchronous response to be used
     * @param unitId the Id of the ObjectGroup
     * @param usage the requested usage
     * @param version the requested version of the usage
     * @param filename the name od the file
     * @param context Vitamcontext
     * @return boolean for test purpose (solve mock issue)
     * @throws UnsupportedEncodingException if unsupported encoding error for input file content
     * @throws VitamClientException         if the client encountered an exception
     */
    // TODO: review this return (should theoretically be a void) because we got mock issue with this class on
    // web application resource
    public static boolean getObjectAsInputStream(AsyncResponse asyncResponse,
        String unitId, String usage, int version, String filename, VitamContext context)
        throws UnsupportedEncodingException, VitamClientException {
        Response response = null;
        try (AccessExternalClient client = AccessExternalClientFactory.getInstance().getClient()) {
            response = client.getObjectStreamByUnitId(
                context,
                unitId, usage, version);
            final AsyncInputStreamHelper helper = new AsyncInputStreamHelper(asyncResponse, response);
            final Response.ResponseBuilder responseBuilder = Response.status(response.getStatus())
                .header(GlobalDataRest.X_QUALIFIER, response.getHeaderString(GlobalDataRest.X_QUALIFIER))
                .header(GlobalDataRest.X_VERSION, response.getHeaderString(GlobalDataRest.X_VERSION))
                .header("Content-Disposition", "filename=\"" + URLDecoder.decode(filename, "UTF-8") + "\"")
                .type(response.getMediaType());
            helper.writeResponse(responseBuilder);
        } finally {
            // close response on error case
            if (response != null && response.getStatus() != Response.Status.OK.getStatusCode()) {
                try {
                    if (response.hasEntity()) {
                        final Object object = response.getEntity();
                        if (object instanceof InputStream) {
                            StreamUtils.closeSilently((InputStream) object);
                        }
                    }
                } catch (final IllegalStateException | ProcessingException e) {
                    LOGGER.debug(e);
                } finally {
                    response.close();
                }
            }
        }
        return true;
    }

    private static void buildOnePathForOneParent(ArrayNode path, JsonNode parent, ArrayNode allPaths,
        JsonNode allParentsRef) {
        final ArrayNode immediateParents = (ArrayNode) parent.get(UiConstants.UNITUPS.getResultCriteria());

        if (immediateParents.size() == 0) {
            // it is a root
            // update allPaths
            allPaths.add(path);
        } else if (immediateParents.size() == 1) {
            // One immediate parent
            final JsonNode oneImmediateParent = allParentsRef.get(immediateParents.get(0).asText());
            path.add(oneImmediateParent);
            buildOnePathForOneParent(path, oneImmediateParent, allPaths, allParentsRef);
        } else {
            // More than one immediate parent
            // Duplicate path so many times as parents
            for (final JsonNode currentParentNode : immediateParents) {
                final String currentParentId = currentParentNode.asText();
                final JsonNode currentParentDetails = allParentsRef.get(currentParentId);

                final ArrayNode pathDuplicate = path.deepCopy();
                pathDuplicate.add(currentParentDetails);
                buildOnePathForOneParent(pathDuplicate, currentParentDetails, allPaths, allParentsRef);
            }
        }
    }

    /**
     * @param unitLifeCycleId the unit lifecycle id to select
     * @param context Vitamcontext
     * @return JsonNode result
     * @throws InvalidParseOperationException if json data not well-formed
     * @throws LogbookClientException         if the request with illegal parameter
     * @throws AccessUnauthorizedException
     */

    public static RequestResponse<LogbookLifecycle> selectUnitLifeCycleById(String unitLifeCycleId,
        VitamContext context)
        throws VitamClientException {
        try (AccessExternalClient client = AccessExternalClientFactory.getInstance().getClient()) {
            return client.selectUnitLifeCycleById(
                context,
                unitLifeCycleId, new Select().getFinalSelectById());

        }
    }

    /**
     * @param query the select query
     * @param context Vitamcontext
     * @return logbook operation result
     * @throws VitamClientException access client exception
     */
    public static RequestResponse<LogbookOperation> selectOperation(JsonNode query, VitamContext context)
        throws VitamClientException {
        try (AccessExternalClient client = AccessExternalClientFactory.getInstance().getClient()) {
            return client.selectOperations(context, query);
        }
    }

    /**
     * @param operationId the operation id
     * @param context Vitamcontext
     * @return logbook operation result
     * @throws VitamClientException
     */
    public static RequestResponse<LogbookOperation> selectOperationbyId(String operationId, VitamContext context)
        throws VitamClientException {
        try (AccessExternalClient client = AccessExternalClientFactory.getInstance().getClient()) {
            return client.selectOperationbyId(
                context,
                operationId,
                new Select().getFinalSelectById());
        }
    }

    /**
     * @param objectGroupLifeCycleId the object lifecycle id to select* @param context   Vitamcontext http
     * @return logbook lifecycle result
     * @throws VitamClientException if the request with illegal parameter
     */

    public static RequestResponse<LogbookLifecycle> selectObjectGroupLifeCycleById(String objectGroupLifeCycleId,
        VitamContext context)
        throws VitamClientException {
        try (AccessExternalClient client = AccessExternalClientFactory.getInstance().getClient()) {
            return client.selectObjectGroupLifeCycleById(
                context,
                objectGroupLifeCycleId, new Select().getFinalSelectById());
        }
    }

    /**
     * @param options for creating query
     * @param context Vitamcontext
     * @return AccessionRegisterSummaryModel result
     * @throws VitamClientException            if the request with illegal parameter
     * @throws InvalidParseOperationException  if json data not well-formed
     * @throws InvalidCreateOperationException if error when create query
     */
    public static RequestResponse<AccessionRegisterSummaryModel> findAccessionRegisterSummary(String options,
        VitamContext context)
        throws VitamClientException, InvalidParseOperationException, InvalidCreateOperationException {
        try (AdminExternalClient adminExternalClient = AdminExternalClientFactory.getInstance().getClient()) {
            final Map<String, Object> optionsMap = JsonHandler.getMapFromString(options);
            final JsonNode query = DslQueryHelper.createSingleQueryDSL(optionsMap);
            return adminExternalClient.findAccessionRegister(
                context,
                query);
        }
    }

    /**
     * @param options for creating query
     * @param context Vitamcontext
     * @return AccessionRegisterSummaryModel result
     * @throws VitamClientException            if the request with illegal parameter
     * @throws InvalidParseOperationException  if json data not well-formed
     * @throws InvalidCreateOperationException if error when create query
     */
    public static RequestResponse<AccessionRegisterSymbolicModel> findAccessionRegisterSymbolic(String options,
        VitamContext context)
        throws VitamClientException, InvalidParseOperationException, InvalidCreateOperationException {
        try (AdminExternalClient adminExternalClient = AdminExternalClientFactory.getInstance().getClient()) {
            final Map<String, Object> optionsMap = JsonHandler.getMapFromString(options);
            final JsonNode query = optionsMap.containsKey("startDate")
                ? DslQueryHelper.createSearchQueryAccessionRegister(optionsMap)
                : DslQueryHelper.createSingleQueryDSL(optionsMap);
            return adminExternalClient.findAccessionRegisterSymbolic(
                context,
                query);
        }
    }

    /**
     * @param id the id of accession register
     * @param options for creating query
     * @param context Vitamcontext
     * @return JsonNode result
     * @throws InvalidParseOperationException        if json data not well-formed
     * @throws AccessExternalClientServerException   if access internal server error
     * @throws AccessExternalClientNotFoundException if access external resource not found
     * @throws InvalidCreateOperationException       if error when create query
     * @throws AccessUnauthorizedException
     */
    public static RequestResponse<JsonNode> findAccessionRegisterDetail(String id, String options, VitamContext context)
        throws InvalidParseOperationException, AccessExternalClientServerException,
        AccessExternalClientNotFoundException, InvalidCreateOperationException, AccessUnauthorizedException {

        try (AdminExternalClient adminExternalClient = AdminExternalClientFactory.getInstance().getClient()) {
            final Map<String, Object> optionsMap = JsonHandler.getMapFromString(options);
            final JsonNode query = DslQueryHelper.createSingleQueryDSL(optionsMap);
            return adminExternalClient
                .getAccessionRegisterDetail(
                    context, id,
                    query);
        }
    }


    /**
     * Starts a Verification process based on a given DSLQuery
     *
     * @param query DSLQuery to execute
     * @param context Vitamcontext
     * @return A RequestResponse contains the created logbookOperation for verification process
     * @throws AccessExternalClientServerException
     * @throws InvalidParseOperationException
     * @throws AccessUnauthorizedException
     */
    @SuppressWarnings("unchecked")
    public static RequestResponse<JsonNode> checkTraceabilityOperation(JsonNode query, VitamContext context)
        throws AccessExternalClientServerException, InvalidParseOperationException, AccessUnauthorizedException {
        try (AdminExternalClient adminExternalClient = AdminExternalClientFactory.getInstance().getClient()) {
            return adminExternalClient
                .checkTraceabilityOperation(
                    context,
                    query);
        }
    }


    /**
     * Extract information from timestamp
     *
     * @param timestamp the timestamp to be used for extraction
     * @return json node containing genTime and issuer certificate information
     * @throws BadRequestException if the timestamp cant be extracted
     */
    public static JsonNode extractInformationFromTimestamp(String timestamp) throws BadRequestException {
        final ObjectNode result = JsonHandler.createObjectNode();
        try {
            ASN1InputStream bIn = new ASN1InputStream(new ByteArrayInputStream(
                org.bouncycastle.util.encoders.Base64.decode(timestamp.getBytes())));
            ASN1Primitive obj = bIn.readObject();
            TimeStampResponse tsResp = new TimeStampResponse(obj.toASN1Primitive().getEncoded());
            SignerId signerId = tsResp.getTimeStampToken().getSID();
            X500Name signerCertIssuer = signerId.getIssuer();
            result.put("genTime", LocalDateUtil.getString(
                LocalDateUtil.fromDate(tsResp.getTimeStampToken().getTimeStampInfo().getGenTime())));
            result.put("signerCertIssuer", signerCertIssuer.toString());
        } catch (TSPException | IOException e) {
            LOGGER.error("Error while transforming timestamp", e);
            throw new BadRequestException("Error while transforming timestamp", e);
        }

        return result;
    }

    /**
     * launch Evidance Audit on selected AU
     *
     * @param query search criteria as DSL query to select AU
     * @param context VitamContext
     * @return a JsonNode for dip results
     * @throws VitamClientException access client exception
     */
    public static RequestResponse evidenceAudit(JsonNode query, VitamContext context) throws VitamClientException {
        try (AdminExternalClient client = AdminExternalClientFactory.getInstance().getClient()) {
            return client.evidenceAudit(context, query);
        }
    }


    /**
     * Launch an probative value export for the request
     *
     * @param query search criteria as DSL query to select AU
     * @param context VitamContext
     * @return a JsonNode for dip results
     * @throws VitamClientException access client exception
     */
    public static RequestResponse exportProbativeValue(JsonNode query, VitamContext context)
        throws VitamClientException {

        try (AdminExternalClient client = AdminExternalClientFactory.getInstance().getClient()) {
            ProbativeValueRequest probativeValueRequest =
                new ProbativeValueRequest(query, Collections.singletonList("BinaryMaster"));
            return client.exportProbativeValue(context, probativeValueRequest);
        }
    }



    /**
     * generate a DIP to be exported
     *
     * @param dipExportRequest search criteria as DSL query
     * @param context VitamContext
     * @return a JsonNode for dip results
     * @throws InvalidParseOperationException unable to parse query
     * @throws VitamClientException           access client exception
     */
    public static RequestResponse<JsonNode> exportDIP(DipExportRequest dipExportRequest, VitamContext context)
        throws VitamClientException {
        try (AccessExternalClientV2 client = AccessExternalClientV2Factory.getInstance().getClient()) {
            return client.exportDIP(context, dipExportRequest);
        }
    }

    /**
     * @param asyncResponse AsyncResponse
     * @param dipId dip id
     * @param context vitam context
     * @return
     * @throws UnsupportedEncodingException
     * @throws VitamClientException
     */
    public static boolean downloadDIP(AsyncResponse asyncResponse, String dipId, VitamContext context)
        throws UnsupportedEncodingException, VitamClientException {
        Response response = null;
        try (AccessExternalClient client = AccessExternalClientFactory.getInstance().getClient()) {
            response = client.getDIPById(
                context,
                dipId);
            final AsyncInputStreamHelper helper = new AsyncInputStreamHelper(asyncResponse, response);
            final Response.ResponseBuilder responseBuilder = Response.status(response.getStatus())
                .header("Content-Disposition",
                    "filename=\"" + URLDecoder.decode("DIP-" + dipId + ".zip", "UTF-8") + "\"")
                .type(response.getMediaType());
            helper.writeResponse(responseBuilder);
        } finally {
            // close response on error case
            if (response != null && response.getStatus() != Response.Status.OK.getStatusCode()) {
                try {
                    if (response.hasEntity()) {
                        final Object object = response.getEntity();
                        if (object instanceof InputStream) {
                            StreamUtils.closeSilently((InputStream) object);
                        }
                    }
                } catch (final IllegalStateException | ProcessingException e) {
                    LOGGER.debug(e);
                } finally {
                    response.close();
                }
            }
        }
        return true;
    }

    public static VitamContext getVitamContext(HttpServletRequest request) {
        return getVitamContext(getTenantId(request), getContractId(request), request);
    }

    public static VitamContext getVitamContext(Integer tenantId, String contractId, String personalCert) {
        return new VitamContext(tenantId)
            .setAccessContract(contractId)
            .setApplicationSessionId(getAppSessionId())
            .setPersonalCertificate(personalCert);
    }

    public static VitamContext getVitamContext(Integer tenantId, String contractId, HttpServletRequest request) {
        String personalCert = getPersonalCertificate(request);
        if (personalCert != null) {
            return new VitamContext(tenantId)
                .setAccessContract(contractId)
                .setApplicationSessionId(getAppSessionId())
                .setPersonalCertificate(personalCert);
        } else {
            return new VitamContext(tenantId)
                .setAccessContract(contractId)
                .setApplicationSessionId(getAppSessionId());
        }
    }

    public static String getPersonalCertificate(HttpServletRequest request) {
        return (String) request.getAttribute(REQUEST_PERSONAL_CERTIFICATE_ATTRIBUTE);
    }

    public static String getContractId(HttpServletRequest request) {
        return request.getHeader(GlobalDataRest.X_ACCESS_CONTRAT_ID);
    }

    public static Integer getTenantId(HttpServletRequest request) {
        Integer tenantId = 0;
        String tenantIdHeader = request.getHeader(GlobalDataRest.X_TENANT_ID);
        if (tenantIdHeader != null) {
            try {
                tenantId = Integer.parseInt(tenantIdHeader);
            } catch (NumberFormatException e) {
                // Do Nothing : Put 0 as tenant Id
                LOGGER.error("No tenant defined, using default tenant");
            }
        }
        return tenantId;
    }



    /**
     * Returns session id for the authenticated user.
     * <p>
     * The application may track each logged user by a unique session id. This session id is passed to vitam and is
     * persisted "as is" in Vitam logbook operations. In case of audit / legal dispute, the application session id can
     * be used for correlation with application user login logs / db.
     *
     * @return application session id
     */
    public static String getAppSessionId() {
        // TODO : Implement session id -> user mapping persistence (login activity journal / logs...).
        return "MyApplicationId-ChangeIt";
    }

}
