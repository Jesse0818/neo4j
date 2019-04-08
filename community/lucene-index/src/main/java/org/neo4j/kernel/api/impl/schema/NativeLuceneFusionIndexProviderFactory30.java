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
package org.neo4j.kernel.api.impl.schema;

import java.io.File;

import org.neo4j.annotations.service.ServiceProvider;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.index.internal.gbptree.RecoveryCleanupWorkCollector;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.api.index.IndexDirectoryStructure;
import org.neo4j.kernel.api.index.IndexProvider;
import org.neo4j.kernel.api.index.IndexProviderDescriptor;
import org.neo4j.kernel.impl.factory.OperationalMode;
import org.neo4j.kernel.impl.index.schema.AbstractIndexProviderFactory;
import org.neo4j.kernel.impl.index.schema.GenericNativeIndexProvider;
import org.neo4j.kernel.impl.index.schema.fusion.FusionIndexProvider;
import org.neo4j.kernel.impl.index.schema.fusion.FusionSlotSelector30;

import static org.neo4j.configuration.GraphDatabaseSettings.SchemaIndex.NATIVE30;
import static org.neo4j.kernel.api.index.IndexDirectoryStructure.directoriesByProvider;
import static org.neo4j.kernel.api.index.IndexProvider.EMPTY;

@ServiceProvider
public class NativeLuceneFusionIndexProviderFactory30 extends NativeLuceneFusionIndexProviderFactory<NativeLuceneFusionIndexProviderFactory30.Dependencies>
{
    public static final String KEY = NATIVE30.providerKey();
    public static final IndexProviderDescriptor DESCRIPTOR = new IndexProviderDescriptor( KEY, NATIVE30.providerVersion() );

    public NativeLuceneFusionIndexProviderFactory30()
    {
        super( KEY );
    }

    @Override
    protected String descriptorString()
    {
        return DESCRIPTOR.toString();
    }

    @Override
    protected IndexProvider internalCreate( PageCache pageCache, File storeDir, FileSystemAbstraction fs, IndexProvider.Monitor monitor, Config config,
            OperationalMode operationalMode, RecoveryCleanupWorkCollector recoveryCleanupWorkCollector )
    {
        return create( pageCache, storeDir, fs, monitor, config, operationalMode, recoveryCleanupWorkCollector );
    }

    public static FusionIndexProvider create( PageCache pageCache, File databaseDirectory, FileSystemAbstraction fs,
            IndexProvider.Monitor monitor, Config config, OperationalMode operationalMode,
            RecoveryCleanupWorkCollector recoveryCleanupWorkCollector )
    {
        IndexDirectoryStructure.Factory childDirectoryStructure = subProviderDirectoryStructure( databaseDirectory );
        boolean readOnly = IndexProviderFactoryUtil.isReadOnly( config, operationalMode );
        boolean archiveFailedIndex = config.get( GraphDatabaseSettings.archive_failed_index );

        GenericNativeIndexProvider generic =
                new GenericNativeIndexProvider( childDirectoryStructure, pageCache, fs, monitor, recoveryCleanupWorkCollector, readOnly, config );
        LuceneIndexProvider lucene = IndexProviderFactoryUtil.luceneProvider( fs, childDirectoryStructure, monitor, config, operationalMode );

        return new FusionIndexProvider( generic, EMPTY, EMPTY, EMPTY, EMPTY, lucene, new FusionSlotSelector30(),
                DESCRIPTOR, directoriesByProvider( databaseDirectory ), fs, archiveFailedIndex );
    }

    private static IndexDirectoryStructure.Factory subProviderDirectoryStructure( File databaseDirectory )
    {
        return NativeLuceneFusionIndexProviderFactory.subProviderDirectoryStructure( databaseDirectory, DESCRIPTOR );
    }

    public interface Dependencies extends AbstractIndexProviderFactory.Dependencies
    {
    }
}