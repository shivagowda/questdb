/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo.vm;

import io.questdb.cairo.CairoException;
import io.questdb.cairo.TableUtils;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.Files;
import io.questdb.std.FilesFacade;
import io.questdb.std.Unsafe;
import io.questdb.std.str.LPSZ;

public class ContiguousMappedReadOnlyMemory extends AbstractContiguousMemory
        implements MappedReadOnlyMemory, ContiguousReadOnlyMemory {

    private static final Log LOG = LogFactory.getLog(ContiguousMappedReadOnlyMemory.class);
    protected long page = -1;
    protected FilesFacade ff;
    protected long fd = -1;
    protected long size = 0;
    private long grownLength;

    public ContiguousMappedReadOnlyMemory(FilesFacade ff, LPSZ name, long pageSize, long size) {
        of(ff, name, pageSize, size);
    }

    public ContiguousMappedReadOnlyMemory(FilesFacade ff, LPSZ name, long size) {
        of(ff, name, 0, size);
    }

    public ContiguousMappedReadOnlyMemory() {
    }

    @Override
    public void close() {
        if (page != -1) {
            ff.munmap(page, size);
            this.size = 0;
            this.page = -1;
        }
        if (fd != -1) {
            ff.close(fd);
            LOG.debug().$("closed [fd=").$(fd).$(']').$();
            fd = -1;
        }
        grownLength = 0;
    }

    @Override
    public void of(FilesFacade ff, LPSZ name, long pageSize, long size) {
        openFile(ff, name);
        map(ff, name, size);
    }

    @Override
    public void of(FilesFacade ff, LPSZ name, long pageSize) {
        openFile(ff, name);
        map(ff, name, ff.length(fd));
    }

    @Override
    public boolean isDeleted() {
        return !ff.exists(fd);
    }

    @Override
    public long getFd() {
        return fd;
    }

    public long getGrownLength() {
        return grownLength;
    }

    @Override
    public long getPageAddress(int pageIndex) {
        return page;
    }

    @Override
    public void setSize(long newSize) {
        grownLength = Math.max(newSize, grownLength);
        if (newSize > size) {
            setSize0(newSize);
        }
    }

    public long size() {
        return size;
    }

    public long addressOf(long offset) {
        assert offset <= size : "offset=" + offset + ", size=" + size + ", fd=" + fd;
        return page + offset;
    }

    @Override
    public void growToFileSize() {
        setSize(ff.length(fd));
    }

    public void of(FilesFacade ff, long fd, LPSZ name, long size) {
        close();
        this.ff = ff;
        this.fd = fd;
        if (fd != -1) {
            map(ff, name, size);
        }
    }

    protected void map(FilesFacade ff, LPSZ name, long size) {
        size = Math.min(ff.length(fd), size);
        this.size = size;
        if (size > 0) {
            this.page = ff.mmap(fd, size, 0, Files.MAP_RO);
            if (page == FilesFacade.MAP_FAILED) {
                long fd = this.fd;
                long fileLen = ff.length(fd);
                close();
                throw CairoException.instance(ff.errno())
                        .put("Could not mmap ").put(name)
                        .put(" [size=").put(size)
                        .put(", fd=").put(fd)
                        .put(", memUsed=").put(Unsafe.getMemUsed())
                        .put(", fileLen=").put(fileLen)
                        .put(']');
            }
        } else {
            this.page = -1;
        }
        LOG.debug().$("open ").$(name).$(" [fd=").$(fd).$(", pageSize=").$(size).$(", size=").$(this.size).$(']').$();
    }

    private void openFile(FilesFacade ff, LPSZ name) {
        close();
        this.ff = ff;
        boolean exists = ff.exists(name);
        if (!exists) {
            throw CairoException.instance(0).put("File not found: ").put(name);
        }
        fd = TableUtils.openRO(ff, name, LOG);
    }

    private void setSize0(long newSize) {
        final long fileSize = ff.length(fd);
        newSize = Math.max(newSize, fileSize);
        long previousSize = size;
        if (previousSize > 0) {
            page = ff.mremap(fd, page, previousSize, newSize, 0, Files.MAP_RO);
        } else {
            assert page == -1;
            page = ff.mmap(fd, newSize, 0, Files.MAP_RO);
        }
        if (page == FilesFacade.MAP_FAILED) {
            long fd = this.fd;
            close();
            throw CairoException.instance(ff.errno()).put("Could not remap file [previousSize=").put(previousSize).put(", newSize=").put(newSize).put(", fd=").put(fd).put(']');
        }
        size = newSize;
    }
}