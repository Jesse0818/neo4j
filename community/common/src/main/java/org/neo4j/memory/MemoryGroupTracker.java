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
package org.neo4j.memory;

public class MemoryGroupTracker implements NamedMemoryPool
{
    private final MemoryPools pools;
    private final MemoryGroup group;
    private final String name;
    private final MemoryPool pool;

    MemoryGroupTracker( MemoryPools pools, MemoryGroup group, String name, long limit )
    {
        this.pools = pools;
        this.group = group;
        this.name = name;
        this.pool = MemoryPools.fromLimit( limit );
    }

    @Override
    public MemoryGroup group()
    {
        return group;
    }

    @Override
    public String name()
    {
        return name;
    }

    @Override
    public void reserve( long bytes )
    {
        pool.reserve( bytes );
    }

    @Override
    public void release( long bytes )
    {
        pool.release( bytes );
    }

    @Override
    public long totalSize()
    {
        return pool.totalSize();
    }

    @Override
    public long used()
    {
        return pool.used();
    }

    @Override
    public long free()
    {
        return pool.free();
    }

    @Override
    public void close()
    {
        pools.releasePool( this );
    }
}
