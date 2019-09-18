/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package counts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.io.fs.EphemeralFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.fs.UncloseableDelegatingFileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.kernel.api.Kernel;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge;
import org.neo4j.kernel.impl.store.MetaDataStore;
import org.neo4j.kernel.impl.transaction.log.checkpoint.CheckPointer;
import org.neo4j.kernel.impl.transaction.log.checkpoint.SimpleTriggerInfo;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.logging.AssertableLogProvider;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.testdirectory.EphemeralTestDirectoryExtension;
import org.neo4j.test.rule.TestDirectory;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;
import static org.neo4j.configuration.GraphDatabaseSettings.index_background_sampling_enabled;
import static org.neo4j.graphdb.Label.label;
import static org.neo4j.internal.kernel.api.security.LoginContext.AUTH_DISABLED;
import static org.neo4j.kernel.api.KernelTransaction.Type.explicit;
import static org.neo4j.logging.AssertableLogProvider.LogMatcherBuilder;
import static org.neo4j.logging.AssertableLogProvider.inLog;

@EphemeralTestDirectoryExtension
class RebuildCountsTest
{
    private static final int ALIENS = 16;
    private static final int HUMANS = 16;
    private static final Label ALIEN = label( "Alien" );
    private static final Label HUMAN = label( "Human" );

    @Inject
    private EphemeralFileSystemAbstraction fileSystem;
    @Inject
    private TestDirectory testDirectory;

    private final AssertableLogProvider userLogProvider = new AssertableLogProvider();
    private final AssertableLogProvider internalLogProvider = new AssertableLogProvider();

    private GraphDatabaseService db;
    private File storeDir;
    private DatabaseManagementService managementService;

    @BeforeEach
    void before() throws IOException
    {
        storeDir = testDirectory.storeDir();
        restart( fileSystem );
    }

    @AfterEach
    void after()
    {
        doCleanShutdown();
    }

    @Test
    void shouldRebuildMissingCountsStoreOnStart() throws IOException, TransactionFailureException
    {
        // given
        createAliensAndHumans();

        // when
        FileSystemAbstraction fs = shutdown();
        deleteCounts( fs );
        restart( fs );

        // then
        Kernel kernel = ((GraphDatabaseAPI) db).getDependencyResolver().resolveDependency( Kernel.class );
        try ( KernelTransaction tx = kernel.beginTransaction( explicit, AUTH_DISABLED ) )
        {
            assertEquals( ALIENS + HUMANS, tx.dataRead().countsForNode( -1 ) );
            assertEquals( ALIENS, tx.dataRead().countsForNode( labelId( ALIEN ) ) );
            assertEquals( HUMANS, tx.dataRead().countsForNode( labelId( HUMAN ) ) );
        }

        // and also
        assertRebuildLogged();
    }

    @Test
    void shouldRebuildMissingCountsStoreAfterRecovery() throws IOException, TransactionFailureException
    {
        // given
        createAliensAndHumans();

        // when
        rotateLog();
        deleteHumans();
        FileSystemAbstraction fs = crash();
        deleteCounts( fs );
        restart( fs );

        // then
        Kernel kernel = ((GraphDatabaseAPI) db).getDependencyResolver().resolveDependency( Kernel.class );
        try ( KernelTransaction tx = kernel.beginTransaction( explicit, AUTH_DISABLED ) )
        {
            assertEquals( ALIENS, tx.dataRead().countsForNode( -1 ) );
            assertEquals( ALIENS, tx.dataRead().countsForNode( labelId( ALIEN ) ) );
            assertEquals( 0, tx.dataRead().countsForNode( labelId( HUMAN ) ) );
        }

        // and also
        assertRebuildLogged();
    }

    private void createAliensAndHumans()
    {
        try ( Transaction tx = db.beginTx() )
        {
            for ( int i = 0; i < ALIENS; i++ )
            {
                tx.createNode( ALIEN );
            }
            for ( int i = 0; i < HUMANS; i++ )
            {
                tx.createNode( HUMAN );
            }
            tx.commit();
        }
    }

    private void deleteHumans()
    {
        try ( Transaction tx = db.beginTx() )
        {
            try ( ResourceIterator<Node> humans = tx.findNodes( HUMAN ) )
            {
                while ( humans.hasNext() )
                {
                    humans.next().delete();
                }
            }
            tx.commit();
        }
    }

    private int labelId( Label alien )
    {
        ThreadToStatementContextBridge contextBridge = ((GraphDatabaseAPI) db).getDependencyResolver()
                .resolveDependency( ThreadToStatementContextBridge.class );
        try ( Transaction tx = db.beginTx() )
        {
            return contextBridge.getKernelTransactionBoundToThisThread( true, ((GraphDatabaseAPI) db).databaseId() )
                    .tokenRead().nodeLabel( alien.name() );
        }
    }

    private void deleteCounts( FileSystemAbstraction snapshot )
    {
        DatabaseLayout databaseLayout = testDirectory.databaseLayout();
        File alpha = databaseLayout.countStoreA();
        File beta = databaseLayout.countStoreB();
        assertTrue( snapshot.deleteFile( alpha ) );
        assertTrue( snapshot.deleteFile( beta ) );
    }

    private FileSystemAbstraction shutdown()
    {
        doCleanShutdown();
        return fileSystem.snapshot();
    }

    private void rotateLog() throws IOException
    {
        ((GraphDatabaseAPI) db).getDependencyResolver()
                               .resolveDependency( CheckPointer.class ).forceCheckPoint( new SimpleTriggerInfo( "test" ) );
    }

    private FileSystemAbstraction crash()
    {
        return fileSystem.snapshot();
    }

    private void restart( FileSystemAbstraction fs ) throws IOException
    {
        if ( db != null )
        {
            managementService.shutdown();
        }

        fs.mkdirs( storeDir );
        managementService = new TestDatabaseManagementServiceBuilder(  storeDir )
                .setUserLogProvider( userLogProvider )
                .setInternalLogProvider( internalLogProvider )
                .setFileSystem( new UncloseableDelegatingFileSystemAbstraction( fs ) )
                .impermanent()
                .setConfig( index_background_sampling_enabled, false )
                .build();
        db = managementService.database( DEFAULT_DATABASE_NAME );
    }

    private void doCleanShutdown()
    {
        try
        {
            managementService.shutdown();
        }
        finally
        {
            db = null;
        }
    }

    private void assertRebuildLogged()
    {
        LogMatcherBuilder matcherBuilder = inLog( MetaDataStore.class );
        internalLogProvider.assertAtLeastOnce( matcherBuilder.warn( containsString( "Missing counts store, rebuilding it." ) ) );
        internalLogProvider.assertAtLeastOnce( matcherBuilder.warn( containsString( "Counts store rebuild completed." ) ) );
    }
}
