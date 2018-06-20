/**
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
 */

package fr.gouv.vitam.processing.integration.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;

import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Sets;
import fr.gouv.vitam.common.CommonMediaType;
import fr.gouv.vitam.common.DataLoader;
import fr.gouv.vitam.common.PropertiesUtils;
import fr.gouv.vitam.common.VitamConfiguration;
import fr.gouv.vitam.common.VitamRuleRunner;
import fr.gouv.vitam.common.VitamServerRunner;
import fr.gouv.vitam.common.client.VitamClientFactoryInterface;
import fr.gouv.vitam.common.format.identification.FormatIdentifierFactory;
import fr.gouv.vitam.common.guid.GUID;
import fr.gouv.vitam.common.guid.GUIDFactory;
import fr.gouv.vitam.common.logging.SysErrLogger;
import fr.gouv.vitam.common.logging.VitamLogger;
import fr.gouv.vitam.common.logging.VitamLoggerFactory;
import fr.gouv.vitam.common.model.ItemStatus;
import fr.gouv.vitam.common.model.ProcessAction;
import fr.gouv.vitam.common.model.ProcessState;
import fr.gouv.vitam.common.model.RequestResponse;
import fr.gouv.vitam.common.model.StatusCode;
import fr.gouv.vitam.common.thread.RunWithCustomExecutor;
import fr.gouv.vitam.common.thread.VitamThreadUtils;
import fr.gouv.vitam.functional.administration.common.server.FunctionalAdminCollections;
import fr.gouv.vitam.functional.administration.rest.AdminManagementMain;
import fr.gouv.vitam.logbook.common.exception.LogbookClientAlreadyExistsException;
import fr.gouv.vitam.logbook.common.exception.LogbookClientBadRequestException;
import fr.gouv.vitam.logbook.common.exception.LogbookClientServerException;
import fr.gouv.vitam.logbook.common.parameters.Contexts;
import fr.gouv.vitam.logbook.common.parameters.LogbookOperationParameters;
import fr.gouv.vitam.logbook.common.parameters.LogbookParametersFactory;
import fr.gouv.vitam.logbook.common.parameters.LogbookTypeProcess;
import fr.gouv.vitam.logbook.operations.client.LogbookOperationsClient;
import fr.gouv.vitam.logbook.operations.client.LogbookOperationsClientFactory;
import fr.gouv.vitam.logbook.rest.LogbookMain;
import fr.gouv.vitam.metadata.core.database.collections.MetadataCollections;
import fr.gouv.vitam.metadata.rest.MetadataMain;
import fr.gouv.vitam.processing.common.model.PauseRecover;
import fr.gouv.vitam.processing.common.model.ProcessStep;
import fr.gouv.vitam.processing.common.model.ProcessWorkflow;
import fr.gouv.vitam.processing.engine.core.monitoring.ProcessMonitoringImpl;
import fr.gouv.vitam.processing.management.client.ProcessingManagementClientFactory;
import fr.gouv.vitam.processing.management.rest.ProcessManagementMain;
import fr.gouv.vitam.storage.engine.client.StorageClientFactory;
import fr.gouv.vitam.worker.server.rest.WorkerMain;
import fr.gouv.vitam.workspace.client.WorkspaceClient;
import fr.gouv.vitam.workspace.client.WorkspaceClientFactory;
import fr.gouv.vitam.workspace.rest.WorkspaceMain;
import org.assertj.core.api.Assertions;
import org.bson.Document;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

public class PausedProcessingIT extends VitamRuleRunner {

    private static final VitamLogger LOGGER = VitamLoggerFactory.getInstance(PausedProcessingIT.class);

    @ClassRule
    public static VitamServerRunner runner =
        new VitamServerRunner(PausedProcessingIT.class, mongoRule.getMongoDatabase().getName(),
            elasticsearchRule.getClusterName(),
            Sets.newHashSet(
                MetadataMain.class,
                WorkerMain.class,
                AdminManagementMain.class,
                LogbookMain.class,
                WorkspaceMain.class,
                ProcessManagementMain.class
            ));

    private static final Integer TENANT_ID = 0;

    private static final long SLEEP_TIME = 20l;
    private static final long NB_TRY = 18000;

    private static final String WORFKLOW_NAME = "PROCESS_SIP_UNITARY";
    private static final String SIP_FOLDER = "SIP";
    private static String CONFIG_SIEGFRIED_PATH;

    private static String SIP_FILE_OK_NAME = "integration-processing/OK_ARBO_complexe.zip";

    private WorkspaceClient workspaceClient;
    // used to check processed elements per step
    private int[] elementCountPerStep = {1, 2, 5, 1, 2, 5, 2, 5, 0, 1, 1};

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        CONFIG_SIEGFRIED_PATH =
            PropertiesUtils.getResourcePath("integration-processing/format-identifiers.conf").toString();
        FormatIdentifierFactory.getInstance().changeConfigurationFile(CONFIG_SIEGFRIED_PATH);
        new DataLoader("integration-processing").prepareData();
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        runAfter();


        try (WorkspaceClient workspaceClient = WorkspaceClientFactory.getInstance().getClient()) {
            workspaceClient.deleteContainer("process", true);
        } catch (Exception e) {
            LOGGER.error(e);
        }
    }

    @After
    public void afterTest() throws Exception {
        MetadataCollections.UNIT.getCollection().deleteMany(new Document());
        MetadataCollections.OBJECTGROUP.getCollection().deleteMany(new Document());
        FunctionalAdminCollections.ACCESSION_REGISTER_SUMMARY.getCollection().deleteMany(new Document());
        FunctionalAdminCollections.ACCESSION_REGISTER_DETAIL.getCollection().deleteMany(new Document());
        FunctionalAdminCollections.ACCESSION_REGISTER_SUMMARY.getEsClient()
            .deleteIndex(FunctionalAdminCollections.ACCESSION_REGISTER_SUMMARY);
        FunctionalAdminCollections.ACCESSION_REGISTER_SUMMARY.getEsClient()
            .addIndex(FunctionalAdminCollections.ACCESSION_REGISTER_SUMMARY);
        FunctionalAdminCollections.ACCESSION_REGISTER_DETAIL.getEsClient()
            .deleteIndex(FunctionalAdminCollections.ACCESSION_REGISTER_DETAIL);
        FunctionalAdminCollections.ACCESSION_REGISTER_DETAIL.getEsClient()
            .addIndex(FunctionalAdminCollections.ACCESSION_REGISTER_DETAIL);
    }

    private void wait(String operationId) {
        int nbTry = 0;
        while (!ProcessingManagementClientFactory.getInstance().getClient().isOperationCompleted(operationId)) {
            try {
                Thread.sleep(SLEEP_TIME);
            } catch (InterruptedException e) {
                SysErrLogger.FAKE_LOGGER.ignoreLog(e);
            }
            if (nbTry == NB_TRY)
                break;
            nbTry++;
        }
    }

    private void createLogbookOperation(GUID operationId, GUID objectId)
        throws LogbookClientBadRequestException, LogbookClientAlreadyExistsException, LogbookClientServerException {

        final LogbookOperationsClient logbookClient = LogbookOperationsClientFactory.getInstance().getClient();

        final LogbookOperationParameters initParameters = LogbookParametersFactory.newLogbookOperationParameters(
            operationId, "Process_SIP_unitary", objectId,
            LogbookTypeProcess.INGEST, StatusCode.STARTED,
            operationId != null ? operationId.toString() : "outcomeDetailMessage",
            operationId);
        logbookClient.create(initParameters);
    }

    @RunWithCustomExecutor
    @Test
    public void testPausedAndPersistedWorkflow() throws Exception {
        ProcessingIT.prepareVitamSession();
        final GUID operationGuid = GUIDFactory.newOperationLogbookGUID(TENANT_ID);
        VitamThreadUtils.getVitamSession().setRequestId(operationGuid);
        final GUID objectGuid = GUIDFactory.newManifestGUID(TENANT_ID);
        final String containerName = objectGuid.getId();
        createLogbookOperation(operationGuid, objectGuid);

        // workspace client dezip SIP in workspace
        final InputStream zipInputStreamSipObject =
            PropertiesUtils.getResourceAsStream(SIP_FILE_OK_NAME);
        workspaceClient = WorkspaceClientFactory.getInstance().getClient();
        workspaceClient.createContainer(containerName);
        workspaceClient.uncompressObject(containerName, SIP_FOLDER, CommonMediaType.ZIP,
            zipInputStreamSipObject);

        ProcessingManagementClientFactory.getInstance().getClient()
            .initVitamProcess(Contexts.DEFAULT_WORKFLOW.name(), containerName,
                WORFKLOW_NAME);
        // wait a little bit

        RequestResponse<JsonNode> resp = ProcessingManagementClientFactory.getInstance().getClient()
            .executeOperationProcess(containerName, WORFKLOW_NAME,
                LogbookTypeProcess.INGEST.toString(), ProcessAction.NEXT.getValue());
        // wait a little bit
        assertNotNull(resp);

        assertEquals(Response.Status.ACCEPTED.getStatusCode(), resp.getStatus());

        wait(containerName);
        ProcessWorkflow processWorkflow =
            ProcessMonitoringImpl.getInstance().findOneProcessWorkflow(containerName, TENANT_ID);

        assertNotNull(processWorkflow);
        assertEquals(ProcessState.PAUSE, processWorkflow.getState());
        assertEquals(StatusCode.OK, processWorkflow.getStatus());
        // wait a little bit

        // shutdown processing
        runner.stopProcessManagementServer(false);
        // wait a little bit
        LOGGER.info("After STOP");
        // restart processing
        runner.startProcessManagementServer();

        LOGGER.info("After RE-START");

        // Next on the old paused ans persisted workflow
        RequestResponse<ItemStatus> ret =
            ProcessingManagementClientFactory.getInstance().getClient()
                .updateOperationActionProcess(ProcessAction.NEXT.getValue(),
                    containerName);
        assertNotNull(ret);

        assertEquals(Response.Status.ACCEPTED.getStatusCode(), ret.getStatus());

        wait(containerName);
        processWorkflow =
            ProcessMonitoringImpl.getInstance().findOneProcessWorkflow(containerName, TENANT_ID);

        assertNotNull(processWorkflow);
        assertEquals(ProcessState.PAUSE, processWorkflow.getState());
        assertEquals(StatusCode.WARNING, processWorkflow.getStatus());


        ret = ProcessingManagementClientFactory.getInstance().getClient()
            .updateOperationActionProcess(ProcessAction.RESUME.getValue(),
                containerName);
        assertNotNull(ret);

        assertEquals(Response.Status.ACCEPTED.getStatusCode(), ret.getStatus());

        wait(containerName);
        processWorkflow =
            ProcessMonitoringImpl.getInstance().findOneProcessWorkflow(containerName, TENANT_ID);

        assertNotNull(processWorkflow);
        assertEquals(ProcessState.COMPLETED, processWorkflow.getState());
        assertEquals(StatusCode.WARNING, processWorkflow.getStatus());
    }

    @RunWithCustomExecutor
    @Test
    public void testPauseProcessWorkflowOnFatalThenRecoverAndResume() throws Exception {
        /**
         * start process, go to first step (pause)
         * stop metadata application
         * check process in pause with status Fatal
         * restart metadata application
         * resume process
         * check process complete with status warning
         */
        testPauseOnFatal(true, false);
    }

    @RunWithCustomExecutor
    @Test
    public void testPauseProcessWorkflowOnFatalThenRecoverThenRestartProcessingAndResume() throws Exception {
        /**
         * start process, go to first step (pause)
         * stop metadata application
         * check process in pause with status Fatal
         * restart metadata application
         * stop and restart Processing
         * resume process
         * check process complete with status warning
         */
        testPauseOnFatal(true, true);
    }

    @RunWithCustomExecutor
    @Test
    public void testPauseProcessWorkflowOnFatalThenResumeWithoutRecover() throws Exception {
        /**
         * start process, go to first step
         * stop metadata application
         * check process in pause with status Fatal
         * resume process
         * check process still in pause with status Fatal
         */
        testPauseOnFatal(false, false);
    }

    /**
     * test pause on fatal then resume
     *
     * @param restartMDServerAfterFatal                if true MD server will be started after pause on Fatal
     * @param stopAndRestartProcessingServerAfterFatal if true Processing Server wil be stopped and restarted after pause on Fatal
     * @throws Exception
     */
    public void testPauseOnFatal(boolean restartMDServerAfterFatal, boolean stopAndRestartProcessingServerAfterFatal)
        throws Exception {
        try {
            // prepare
            ProcessingIT.prepareVitamSession();
            final GUID operationGuid = GUIDFactory.newOperationLogbookGUID(TENANT_ID);
            VitamThreadUtils.getVitamSession().setRequestId(operationGuid);
            final GUID objectGuid = GUIDFactory.newManifestGUID(TENANT_ID);
            final String containerName = objectGuid.getId();
            createLogbookOperation(operationGuid, objectGuid);

            // workspace client dezip SIP in workspace
            final InputStream zipInputStreamSipObject = PropertiesUtils.getResourceAsStream(SIP_FILE_OK_NAME);
            workspaceClient = WorkspaceClientFactory.getInstance().getClient();
            workspaceClient.createContainer(containerName);
            workspaceClient.uncompressObject(containerName, SIP_FOLDER, CommonMediaType.ZIP, zipInputStreamSipObject);

            ProcessingManagementClientFactory.getInstance().getClient()
                .initVitamProcess(Contexts.DEFAULT_WORKFLOW.name(), containerName,
                    WORFKLOW_NAME);

            // process execute
            RequestResponse<JsonNode> resp = ProcessingManagementClientFactory.getInstance().getClient()
                .executeOperationProcess(containerName, WORFKLOW_NAME,
                    LogbookTypeProcess.INGEST.toString(), ProcessAction.NEXT.getValue());
            assertNotNull(resp);
            assertEquals(Response.Status.ACCEPTED.getStatusCode(), resp.getStatus());

            // check process
            wait(containerName);
            ProcessWorkflow processWorkflow =
                ProcessMonitoringImpl.getInstance().findOneProcessWorkflow(containerName, TENANT_ID);
            assertNotNull(processWorkflow);
            assertEquals(ProcessState.PAUSE, processWorkflow.getState());
            assertEquals(StatusCode.OK, processWorkflow.getStatus());

            // stop MD server while process in pause
            // shutdown metadata, this should generate FATAl status
            runner.stopMetadataServer(false);

            // resume process
            RequestResponse<ItemStatus> ret =
                ProcessingManagementClientFactory.getInstance().getClient()
                    .updateOperationActionProcess(ProcessAction.RESUME.getValue(), containerName);
            assertNotNull(ret);
            assertEquals(Response.Status.ACCEPTED.getStatusCode(), ret.getStatus());

            // check process status
            wait(containerName);
            processWorkflow = ProcessMonitoringImpl.getInstance().findOneProcessWorkflow(containerName, TENANT_ID);
            assertNotNull(processWorkflow);
            assertEquals(ProcessState.PAUSE, processWorkflow.getState());
            assertEquals(StatusCode.FATAL, processWorkflow.getStatus());
            assertEquals(PauseRecover.RECOVER_FROM_API_PAUSE, processWorkflow.getPauseRecover());

            // restart metadata
            if (restartMDServerAfterFatal) {
                runner.startMetadataServer();
            }

            // stop and restart processing
            if (stopAndRestartProcessingServerAfterFatal) {
                // shutdown processing
                runner.stopProcessManagementServer(false);
                // restart processing
                runner.startProcessManagementServer();
            }

            // resume process
            ret = ProcessingManagementClientFactory.getInstance().getClient()
                .updateOperationActionProcess(ProcessAction.RESUME.getValue(), containerName);
            assertNotNull(ret);
            assertEquals(Response.Status.ACCEPTED.getStatusCode(), ret.getStatus());

            // check process status
            wait(containerName);
            processWorkflow = ProcessMonitoringImpl.getInstance().findOneProcessWorkflow(containerName, TENANT_ID);
            assertNotNull(processWorkflow);
            // if MD server restarted process should complete with status Warning, otherwise it must still in pause with status Fatal
            if (restartMDServerAfterFatal) {
                assertEquals(restartMDServerAfterFatal ? ProcessState.COMPLETED : ProcessState.PAUSE,
                    processWorkflow.getState());
                assertEquals(restartMDServerAfterFatal ? StatusCode.WARNING : StatusCode.FATAL,
                    processWorkflow.getStatus());

                // check if all steps are OK
                checkAllSteps(processWorkflow);
            } else {
                assertEquals(ProcessState.PAUSE, processWorkflow.getState());
                assertEquals(StatusCode.FATAL, processWorkflow.getStatus());
            }
        } finally {
            // restart metadata if not already done
            if (!restartMDServerAfterFatal) {
                runner.startMetadataServer();
            }
        }
    }

    private void checkAllSteps(ProcessWorkflow processWorkflow) {
        int stepIndex = 0;
        for (ProcessStep step : processWorkflow.getSteps()) {
            // check status
            assertTrue(step.getStepStatusCode().equals(StatusCode.OK) ||
                step.getStepStatusCode().equals(StatusCode.WARNING));

            // check processed elements
            Assertions.assertThat(step.getElementProcessed()).isEqualTo(step.getElementToProcess());
            Assertions.assertThat(step.getElementProcessed()).isEqualTo(elementCountPerStep[stepIndex++]);
        }
    }

}
