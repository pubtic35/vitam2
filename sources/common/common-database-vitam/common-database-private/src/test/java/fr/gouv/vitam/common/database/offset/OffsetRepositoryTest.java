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
package fr.gouv.vitam.common.database.offset;

import com.mongodb.client.MongoCollection;
import fr.gouv.vitam.common.database.server.mongodb.MongoDbAccess;
import fr.gouv.vitam.common.database.server.mongodb.SimpleMongoDBAccess;
import fr.gouv.vitam.common.guid.GUIDFactory;
import fr.gouv.vitam.common.mongo.MongoRule;
import org.assertj.core.groups.Tuple;
import org.bson.Document;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import static fr.gouv.vitam.common.database.collections.VitamCollection.getMongoClientOptions;
import static org.assertj.core.api.Assertions.assertThat;

public class OffsetRepositoryTest {

    private final static String DB = "vitam-test";
    private final static String COLLECTIONNAME = "Offset" + GUIDFactory.newGUID().getId();

    @ClassRule
    public static MongoRule mongoRule = new MongoRule(getMongoClientOptions(), DB, COLLECTIONNAME);

    private OffsetRepository offsetRepository;

    private MongoCollection<Document> mongoCollection;

    @AfterClass
    public static void beforeClass() {
        mongoRule.handleAfterClass();
    }

    @Before
    public void setUp() throws Exception {
        MongoDbAccess mongoDbAccess = new SimpleMongoDBAccess(mongoRule.getMongoClient(), DB);
        offsetRepository = new OffsetRepository(mongoDbAccess, COLLECTIONNAME);
        mongoCollection = mongoRule.getMongoCollection(COLLECTIONNAME);
    }

    @After
    public void after() {
        mongoRule.handleAfter();
    }

    @Test
    public void should_insert_if_not_exist() {
        // Given / When
        offsetRepository.createOrUpdateOffset(1, "Unit", 1);

        // Then
        assertThat(mongoCollection.count()).isEqualTo(1);
        assertThat(mongoCollection.find())
            .extracting("offset", "collection", "_tenant")
            .contains(Tuple.tuple(1L, "Unit", 1));
    }

    @Test
    public void should_update_document_if_already_present() {
        // Given
        offsetRepository.createOrUpdateOffset(1, "Unit", 1);

        // When
        offsetRepository.createOrUpdateOffset(1, "Unit", 15);

        // Then
        assertThat(mongoCollection.count()).isEqualTo(1);
        assertThat(mongoCollection.find())
            .extracting("offset", "collection", "_tenant")
            .contains(Tuple.tuple(15L, "Unit", 1));
    }

    @Test
    public void should_select_offset_if_not_present() {
        // Given / When
        Long offset = offsetRepository.findOffsetBy(1, "Unit");

        // Then
        assertThat(offset).isEqualTo(0);
    }

    @Test
    public void should_select_offset_if_already_present() {
        // Given
        offsetRepository.createOrUpdateOffset(1, "Unit", 12);

        // When
        Long offset = offsetRepository.findOffsetBy(1, "Unit");

        // Then
        assertThat(offset).isEqualTo(12);
    }

}
