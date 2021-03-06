/*
 * Copyright (c) 2002-2020 "Neo4j,"
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
package org.neo4j.kernel.recovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.impl.transaction.SimpleLogVersionRepository;
import org.neo4j.kernel.impl.transaction.SimpleTransactionIdStore;
import org.neo4j.kernel.impl.transaction.log.FlushablePositionAwareChecksumChannel;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.kernel.impl.transaction.log.LogPositionMarker;
import org.neo4j.kernel.impl.transaction.log.entry.CheckPoint;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryReader;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryStart;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryVersion;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryWriter;
import org.neo4j.kernel.impl.transaction.log.files.LogFile;
import org.neo4j.kernel.impl.transaction.log.files.LogFiles;
import org.neo4j.kernel.impl.transaction.log.files.LogFilesBuilder;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.kernel.recovery.LogTailScanner.LogTailInformation;
import org.neo4j.monitoring.Monitors;
import org.neo4j.storageengine.api.LogVersionRepository;
import org.neo4j.storageengine.api.StoreId;
import org.neo4j.storageengine.api.TransactionIdStore;
import org.neo4j.test.extension.EphemeralNeo4jLayoutExtension;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.pagecache.EphemeralPageCacheExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.neo4j.io.pagecache.tracing.cursor.PageCursorTracer.NULL;
import static org.neo4j.kernel.impl.transaction.log.TestLogEntryReader.logEntryReader;
import static org.neo4j.kernel.recovery.LogTailScanner.NO_TRANSACTION_ID;
import static org.neo4j.storageengine.api.TransactionIdStore.BASE_TX_CHECKSUM;

@EphemeralPageCacheExtension
@EphemeralNeo4jLayoutExtension
class LogTailScannerTest
{
    @Inject
    private FileSystemAbstraction fs;
    @Inject
    private PageCache pageCache;
    @Inject
    private DatabaseLayout databaseLayout;

    private final LogEntryReader reader = logEntryReader();

    private LogTailScanner tailScanner;

    private final Monitors monitors = new Monitors();
    private LogFiles logFiles;
    private final byte latestLogEntryVersion = LogEntryVersion.LATEST.version();
    private LogVersionRepository logVersionRepository;
    private TransactionIdStore transactionIdStore;

    private static Stream<Arguments> params()
    {
        return Stream.of(
            arguments( 1, 2 ),
            arguments( 42, 43 )
        );
    }

    @BeforeEach
    void setUp() throws IOException
    {
        logVersionRepository = new SimpleLogVersionRepository();
        transactionIdStore = new SimpleTransactionIdStore();
        logFiles = LogFilesBuilder
                .activeFilesBuilder( databaseLayout, fs, pageCache )
                .withLogVersionRepository( logVersionRepository )
                .withTransactionIdStore( transactionIdStore )
                .withLogEntryReader( logEntryReader() )
                .withStoreId( StoreId.UNKNOWN )
                .build();
        tailScanner = new LogTailScanner( logFiles, reader, monitors );
    }

    @Test
    void detectMissingLogFiles()
    {
        LogTailInformation tailInformation = tailScanner.getTailInformation();
        assertTrue( tailInformation.logsMissing() );
        assertTrue( tailInformation.isRecoveryRequired() );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void noLogFilesFound( int startLogVersion, int endLogVersion )
    {
        // given no files
        setupLogFiles( endLogVersion );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( false, false, NO_TRANSACTION_ID, -1, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void oneLogFileNoCheckPoints( int startLogVersion, int endLogVersion )
    {
        // given
        setupLogFiles( endLogVersion, logFile() );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( false, false, NO_TRANSACTION_ID, endLogVersion, logTailInformation );
        assertFalse( logTailInformation.logsMissing() );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void oneLogFileNoCheckPointsOneStart( int startLogVersion, int endLogVersion )
    {
        // given
        long txId = 10;
        setupLogFiles( endLogVersion, logFile( start(), commit( txId ) ) );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( false, true, txId, endLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void twoLogFilesNoCheckPoints( int startLogVersion, int endLogVersion )
    {
        // given
        setupLogFiles( endLogVersion, logFile(), logFile() );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( false, false, NO_TRANSACTION_ID, startLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void twoLogFilesNoCheckPointsOneStart( int startLogVersion, int endLogVersion )
    {
        // given
        long txId = 21;
        setupLogFiles( endLogVersion, logFile(), logFile( start(), commit( txId ) ) );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( false, true, txId, startLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void twoLogFilesNoCheckPointsOneStartWithoutCommit( int startLogVersion, int endLogVersion )
    {
        // given
        setupLogFiles( endLogVersion, logFile(), logFile( start() ) );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( false, true, NO_TRANSACTION_ID, startLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void twoLogFilesNoCheckPointsTwoCommits( int startLogVersion, int endLogVersion )
    {
        // given
        long txId = 21;
        setupLogFiles( endLogVersion, logFile(), logFile( start(), commit( txId ), start(), commit( txId + 1 ) ) );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( false, true, txId, startLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void twoLogFilesCheckPointTargetsPrevious( int startLogVersion, int endLogVersion )
    {
        // given
        long txId = 6;
        PositionEntry position = position();
        setupLogFiles( endLogVersion,
            logFile( start(), commit( txId - 1 ), position ),
            logFile( start(), commit( txId ) ),
            logFile( checkPoint( position ) ) );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( true, true, txId, endLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void twoLogFilesStartAndCommitInDifferentFiles( int startLogVersion, int endLogVersion )
    {
        // given
        long txId = 6;
        setupLogFiles( endLogVersion,
            logFile( start() ),
            logFile( commit( txId ) ) );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( false, true, 6, startLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void latestLogFileContainingACheckPointOnly( int startLogVersion, int endLogVersion )
    {
        // given
        setupLogFiles( endLogVersion, logFile( checkPoint() ) );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( true, false, NO_TRANSACTION_ID, endLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void latestLogFileContainingACheckPointAndAStartBefore( int startLogVersion, int endLogVersion )
    {
        // given
        setupLogFiles( endLogVersion, logFile( start(), commit( 1 ), checkPoint() ) );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( true, false, NO_TRANSACTION_ID, endLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void bigFileLatestCheckpointFindsStartAfter( int startLogVersion, int endLogVersion ) throws IOException
    {
        long firstTxAfterCheckpoint = Integer.MAX_VALUE + 4L;

        LogTailScanner tailScanner =
            new FirstTxIdConfigurableTailScanner( firstTxAfterCheckpoint, logFiles, reader, monitors );
        LogEntryStart startEntry = new LogEntryStart( 3L, 4L, 0, new byte[]{5, 6},
            new LogPosition( endLogVersion, Integer.MAX_VALUE + 17L ) );
        CheckPoint checkPoint = new CheckPoint( new LogPosition( endLogVersion, 16L ) );
        LogTailInformation logTailInformation = tailScanner.checkpointTailInformation( endLogVersion, startEntry,
            endLogVersion, latestLogEntryVersion, checkPoint, false, StoreId.UNKNOWN );

        assertLatestCheckPoint( true, true, firstTxAfterCheckpoint, endLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void twoLogFilesSecondIsCorruptedBeforeCommit( int startLogVersion, int endLogVersion ) throws IOException
    {
        setupLogFiles( endLogVersion, logFile( checkPoint() ), logFile( start(), commit( 2 ) ) );

        File highestLogFile = logFiles.getHighestLogFile();
        fs.truncate( highestLogFile, fs.getFileSize( highestLogFile ) - 3 );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( true, true, NO_TRANSACTION_ID, startLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void twoLogFilesSecondIsCorruptedBeforeAfterCommit( int startLogVersion, int endLogVersion ) throws IOException
    {
        int firstTxId = 2;
        setupLogFiles( endLogVersion, logFile( checkPoint() ), logFile( start(), commit( firstTxId ), start(), commit( 3 ) ) );

        File highestLogFile = logFiles.getHighestLogFile();
        fs.truncate( highestLogFile, fs.getFileSize( highestLogFile ) - 3 );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( true, true, firstTxId, startLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void latestLogFileContainingACheckPointAndAStartAfter( int startLogVersion, int endLogVersion )
    {
        // given
        long txId = 35;
        StartEntry start = start();
        setupLogFiles( endLogVersion, logFile( start, commit( txId ), checkPoint( start ) ) );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( true, true, txId, endLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void latestLogFileContainingMultipleCheckPointsOneStartInBetween( int startLogVersion, int endLogVersion )
    {
        // given
        setupLogFiles( endLogVersion, logFile( checkPoint(), start(), commit( 1 ), checkPoint() ) );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( true, false, NO_TRANSACTION_ID, endLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void latestLogFileContainingMultipleCheckPointsOneStartAfterBoth( int startLogVersion, int endLogVersion )
    {
        // given
        long txId = 11;
        setupLogFiles( endLogVersion, logFile( checkPoint(), checkPoint(), start(), commit( txId ) ) );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( true, true, txId, endLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void olderLogFileContainingACheckPointAndNewerFileContainingAStart( int startLogVersion, int endLogVersion )
    {
        // given
        long txId = 11;
        StartEntry start = start();
        setupLogFiles( endLogVersion, logFile( checkPoint() ), logFile( start, commit( txId ) ) );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( true, true, txId, startLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void olderLogFileContainingACheckPointAndNewerFileIsEmpty( int startLogVersion, int endLogVersion )
    {
        // given
        StartEntry start = start();
        setupLogFiles( endLogVersion, logFile( start, commit( 1 ), checkPoint() ), logFile() );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( true, false, NO_TRANSACTION_ID, startLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void olderLogFileContainingAStartAndNewerFileContainingACheckPointPointingToAPreviousPositionThanStart( int startLogVersion, int endLogVersion )
    {
        // given
        long txId = 123;
        StartEntry start = start();
        setupLogFiles( endLogVersion, logFile( start, commit( txId ) ), logFile( checkPoint( start ) ) );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( true, true, txId, endLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void olderLogFileContainingAStartAndNewerFileContainingACheckPointPointingToAPreviousPositionThanStartWithoutCommit( int startLogVersion,
        int endLogVersion )
    {
        // given
        StartEntry start = start();
        setupLogFiles( endLogVersion, logFile( start ), logFile( checkPoint( start ) ) );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( true, false, NO_TRANSACTION_ID, endLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void olderLogFileContainingAStartAndNewerFileContainingACheckPointPointingToALaterPositionThanStart( int startLogVersion, int endLogVersion )
    {
        // given
        PositionEntry position = position();
        setupLogFiles( endLogVersion, logFile( start(), commit( 3 ), position ), logFile( checkPoint( position ) ) );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( true, false, NO_TRANSACTION_ID, endLogVersion, logTailInformation );
    }

    @ParameterizedTest
    @MethodSource( "params" )
    void latestLogEmptyStartEntryBeforeAndAfterCheckPointInTheLastButOneLog( int startLogVersion, int endLogVersion )
    {
        // given
        long txId = 432;
        setupLogFiles( endLogVersion, logFile( start(), commit( 1 ), checkPoint(), start(), commit( txId ) ), logFile() );

        // when
        LogTailInformation logTailInformation = tailScanner.getTailInformation();

        // then
        assertLatestCheckPoint( true, true, txId, startLogVersion, logTailInformation );
    }

    // === Below is code for helping the tests above ===

    private void setupLogFiles( long endLogVersion, LogCreator... logFiles )
    {
        Map<Entry, LogPosition> positions = new HashMap<>();
        long version = endLogVersion - logFiles.length;
        for ( LogCreator logFile : logFiles )
        {
            logFile.create( ++version, positions );
        }
    }

    private LogCreator logFile( Entry... entries )
    {
        return ( logVersion, positions ) ->
        {
            try
            {
                AtomicLong lastTxId = new AtomicLong();
                logVersionRepository.setCurrentLogVersion( logVersion, NULL );
                LifeSupport logFileLife = new LifeSupport();
                logFileLife.start();
                logFileLife.add( logFiles );
                LogFile logFile = logFiles.getLogFile();
                int previousChecksum = BASE_TX_CHECKSUM;
                try
                {
                    FlushablePositionAwareChecksumChannel writeChannel = logFile.getWriter();
                    LogPositionMarker positionMarker = new LogPositionMarker();
                    LogEntryWriter writer = new LogEntryWriter( writeChannel );
                    for ( Entry entry : entries )
                    {
                        LogPosition currentPosition = writeChannel.getCurrentPosition( positionMarker ).newPosition();
                        positions.put( entry, currentPosition );
                        if ( entry instanceof StartEntry )
                        {
                            writer.writeStartEntry( 0, 0, previousChecksum, new byte[0] );
                        }
                        else if ( entry instanceof CommitEntry )
                        {
                            CommitEntry commitEntry = (CommitEntry) entry;
                            previousChecksum = writer.writeCommitEntry( commitEntry.txId, 0 );
                            lastTxId.set( commitEntry.txId );
                        }
                        else if ( entry instanceof CheckPointEntry )
                        {
                            CheckPointEntry checkPointEntry = (CheckPointEntry) entry;
                            Entry target = checkPointEntry.withPositionOfEntry;
                            LogPosition logPosition = target != null ? positions.get( target ) : currentPosition;
                            assert logPosition != null : "No registered log position for " + target;
                            writer.writeCheckPointEntry( logPosition );
                        }
                        else if ( entry instanceof PositionEntry )
                        {
                            // Don't write anything, this entry is just for registering a position so that
                            // another CheckPointEntry can refer to it
                        }
                        else
                        {
                            throw new IllegalArgumentException( "Unknown entry " + entry );
                        }
                    }
                }
                finally
                {
                    logFileLife.shutdown();
                }
            }
            catch ( IOException e )
            {
                throw new UncheckedIOException( e );
            }
        };
    }

    @FunctionalInterface
    interface LogCreator
    {
        void create( long version, Map<Entry, LogPosition> positions );
    }

    // Marker interface, helping compilation/test creation
    interface Entry
    {
    }

    private static StartEntry start()
    {
        return new StartEntry();
    }

    private static CommitEntry commit( long txId )
    {
        return new CommitEntry( txId );
    }

    private static CheckPointEntry checkPoint()
    {
        return checkPoint( null/*means self-position*/ );
    }

    private static CheckPointEntry checkPoint( Entry forEntry )
    {
        return new CheckPointEntry( forEntry );
    }

    private static PositionEntry position()
    {
        return new PositionEntry();
    }

    private static class StartEntry implements Entry
    {
    }

    private static class CommitEntry implements Entry
    {
        final long txId;

        CommitEntry( long txId )
        {
            this.txId = txId;
        }
    }

    private static class CheckPointEntry implements Entry
    {
        final Entry withPositionOfEntry;

        CheckPointEntry( Entry withPositionOfEntry )
        {
            this.withPositionOfEntry = withPositionOfEntry;
        }
    }

    private static class PositionEntry implements Entry
    {
    }

    private static void assertLatestCheckPoint( boolean hasCheckPointEntry, boolean commitsAfterLastCheckPoint,
        long firstTxIdAfterLastCheckPoint, long logVersion, LogTailInformation logTailInformation )
    {
        assertEquals( hasCheckPointEntry, logTailInformation.lastCheckPoint != null );
        assertEquals( commitsAfterLastCheckPoint, logTailInformation.commitsAfterLastCheckpoint() );
        if ( commitsAfterLastCheckPoint )
        {
            assertEquals( firstTxIdAfterLastCheckPoint, logTailInformation.firstTxIdAfterLastCheckPoint );
        }
        assertEquals( logVersion, logTailInformation.oldestLogVersionFound );
    }

    private static class FirstTxIdConfigurableTailScanner extends LogTailScanner
    {

        private final long txId;

        FirstTxIdConfigurableTailScanner( long txId, LogFiles logFiles, LogEntryReader logEntryReader, Monitors monitors )
        {
            super( logFiles, logEntryReader, monitors );
            this.txId = txId;
        }

        @Override
        protected ExtractedTransactionRecord extractFirstTxIdAfterPosition( LogPosition initialPosition, long maxLogVersion )
        {
            return new ExtractedTransactionRecord( txId );
        }
    }
}
