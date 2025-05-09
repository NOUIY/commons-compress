/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.commons.compress.archivers.dump;

import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.compress.archivers.ArchiveEntry;

/**
 * This class represents an entry in a Dump archive. It consists of the entry's header, the entry's File and any extended attributes.
 * <p>
 * DumpEntries that are created from the header bytes read from an archive are instantiated with the DumpArchiveEntry( byte[] ) constructor. These entries will
 * be used when extracting from or listing the contents of an archive. These entries have their header filled in using the header bytes. They also set the File
 * to null, since they reference an archive entry not a file.
 * <p>
 * DumpEntries can also be constructed from nothing but a name. This allows the programmer to construct the entry by hand, for instance when only an InputStream
 * is available for writing to the archive, and the header information is constructed from other information. In this case the header fields are set to defaults
 * and the File is set to null.
 *
 * <p>
 * The C structure for a Dump Entry's header is:
 *
 * <pre>
 * #define TP_BSIZE    1024          // size of each file block
 * #define NTREC       10            // number of blocks to write at once
 * #define HIGHDENSITYTREC 32        // number of blocks to write on high-density tapes
 * #define TP_NINDIR   (TP_BSIZE/2)  // number if indirect inodes in record
 * #define TP_NINOS    (TP_NINDIR / sizeof (int32_t))
 * #define LBLSIZE     16
 * #define NAMELEN     64
 *
 * #define OFS_MAGIC     (int) 60011  // old format magic value
 * #define NFS_MAGIC     (int) 60012  // new format magic value
 * #define FS_UFS2_MAGIC (int) 0x19540119
 * #define CHECKSUM      (int) 84446  // constant used in checksum algorithm
 *
 * struct  s_spcl {
 *   int32_t c_type;             // record type (see below)
 *   int32_t <strong>c_date</strong>;             // date of this dump
 *   int32_t <strong>c_ddate</strong>;            // date of previous dump
 *   int32_t c_volume;           // dump volume number
 *   u_int32_t c_tapea;          // logical block of this record
 *   dump_ino_t c_ino;           // number of inode
 *   int32_t <strong>c_magic</strong>;            // magic number (see above)
 *   int32_t c_checksum;         // record checksum
 * #ifdef  __linux__
 *   struct  new_bsd_inode c_dinode;
 * #else
 * #ifdef sunos
 *   struct  new_bsd_inode c_dinode;
 * #else
 *   struct  dinode  c_dinode;   // ownership and mode of inode
 * #endif
 * #endif
 *   int32_t c_count;            // number of valid c_addr entries
 *   union u_data c_data;        // see above
 *   char    <strong>c_label[LBLSIZE]</strong>;   // dump label
 *   int32_t <strong>c_level</strong>;            // level of this dump
 *   char    <strong>c_filesys[NAMELEN]</strong>; // name of dumpped file system
 *   char    <strong>c_dev[NAMELEN]</strong>;     // name of dumpped device
 *   char    <strong>c_host[NAMELEN]</strong>;    // name of dumpped host
 *   int32_t c_flags;            // additional information (see below)
 *   int32_t c_firstrec;         // first record on volume
 *   int32_t c_ntrec;            // blocksize on volume
 *   int32_t c_extattributes;    // additional inode info (see below)
 *   int32_t c_spare[30];        // reserved for future uses
 * } s_spcl;
 *
 * //
 * // flag values
 * //
 * #define DR_NEWHEADER     0x0001  // new format tape header
 * #define DR_NEWINODEFMT   0x0002  // new format inodes on tape
 * #define DR_COMPRESSED    0x0080  // dump tape is compressed
 * #define DR_METAONLY      0x0100  // only the metadata of the inode has been dumped
 * #define DR_INODEINFO     0x0002  // [SIC] TS_END header contains c_inos information
 * #define DR_EXTATTRIBUTES 0x8000
 *
 * //
 * // extattributes inode info
 * //
 * #define EXT_REGULAR         0
 * #define EXT_MACOSFNDRINFO   1
 * #define EXT_MACOSRESFORK    2
 * #define EXT_XATTR           3
 *
 * // used for EA on tape
 * #define EXT2_GOOD_OLD_INODE_SIZE    128
 * #define EXT2_XATTR_MAGIC        0xEA020000  // block EA
 * #define EXT2_XATTR_MAGIC2       0xEA020001  // in inode EA
 * </pre>
 * <p>
 * The fields in <strong>bold</strong> are the same for all blocks. (This permitted multiple dumps to be written to a single tape.)
 * </p>
 *
 * <p>
 * The C structure for the inode (file) information is:
 *
 * <pre>
 * struct bsdtimeval {           //  **** alpha-*-linux is deviant
 *   __u32   tv_sec;
 *   __u32   tv_usec;
 * };
 *
 * #define NDADDR      12
 * #define NIADDR       3
 *
 * //
 * // This is the new (4.4) BSD inode structure
 * // copied from the FreeBSD 2.0 &lt;ufs/ufs/dinode.h&gt; include file
 * //
 * struct new_bsd_inode {
 *   __u16       di_mode;           // file type, standard Unix permissions
 *   __s16       di_nlink;          // number of hard links to file.
 *   union {
 *      __u16       oldids[2];
 *      __u32       inumber;
 *   }           di_u;
 *   u_quad_t    di_size;           // file size
 *   struct bsdtimeval   di_atime;  // time file was last accessed
 *   struct bsdtimeval   di_mtime;  // time file was last modified
 *   struct bsdtimeval   di_ctime;  // time file was created
 *   __u32       di_db[NDADDR];
 *   __u32       di_ib[NIADDR];
 *   __u32       di_flags;          //
 *   __s32       di_blocks;         // number of disk blocks
 *   __s32       di_gen;            // generation number
 *   __u32       di_uid;            // user id (see /etc/passwd)
 *   __u32       di_gid;            // group id (see /etc/group)
 *   __s32       di_spare[2];       // unused
 * };
 * </pre>
 * <p>
 * It is important to note that the header DOES NOT have the name of the file. It can't since hard links mean that you may have multiple file names for a single
 * physical file. You must read the contents of the directory entries to learn the mapping(s) from file name to inode.
 * </p>
 *
 * <p>
 * The C structure that indicates if a specific block is a real block that contains data or is a sparse block that is not persisted to the disk is:
 * </p>
 *
 * <pre>
 * #define TP_BSIZE    1024
 * #define TP_NINDIR   (TP_BSIZE/2)
 *
 * union u_data {
 *   char    s_addrs[TP_NINDIR]; // 1 =&gt; data; 0 =&gt; hole in inode
 *   int32_t s_inos[TP_NINOS];   // table of first inode on each volume
 * } u_data;
 * </pre>
 *
 * @NotThreadSafe
 */
public class DumpArchiveEntry implements ArchiveEntry {

    /**
     * Enumerates permissions with values.
     */
    public enum PERMISSION {
        // Note: The arguments are octal values

        /**
         * Permission SETUID (octal value 04000).
         */
        SETUID(04000),

        /**
         * Permission SETGUI (octal value 02000).
         */
        SETGUI(02000),

        /**
         * Permission STICKY (octal value 01000).
         */
        STICKY(01000),

        /**
         * Permission USER_READ (octal value 00400).
         */
        USER_READ(00400),

        /**
         * Permission USER_WRITE (octal value 00200).
         */
        USER_WRITE(00200),

        /**
         * Permission USER_EXEC (octal value 00100).
         */
        USER_EXEC(00100),

        /**
         * Permission GROUP_READ (octal value 00040).
         */
        GROUP_READ(00040),

        /**
         * Permission GROUP_WRITE (octal value 00020).
         */
        GROUP_WRITE(00020),

        /**
         * Permission 00020 (octal value 00010).
         */
        GROUP_EXEC(00010),

        /**
         * Permission WORLD_READ (octal value 00004).
         */
        WORLD_READ(00004),

        /**
         * Permission WORLD_WRITE (octal value 00002).
         */
        WORLD_WRITE(00002),

        /**
         * Permission WORLD_EXEC (octal value 00001).
         */
        WORLD_EXEC(00001);

        /**
         * Finds a matching set of enumeration values for the given code.
         *
         * @param code a code.
         * @return a Set of values, never null.
         */
        public static Set<PERMISSION> find(final int code) {
            final Set<PERMISSION> set = new HashSet<>();
            for (final PERMISSION p : values()) {
                if ((code & p.code) == p.code) {
                    set.add(p);
                }
            }
            if (set.isEmpty()) {
                return Collections.emptySet();
            }
            return EnumSet.copyOf(set);
        }

        private final int code;

        PERMISSION(final int code) {
            this.code = code;
        }
    }

    /**
     * Archive entry as stored on tape. There is one TSH for (at most) every 512k in the file.
     */
    static final class TapeSegmentHeader {
        private DumpArchiveConstants.SEGMENT_TYPE type;
        private int volume;
        private int ino;
        private int count;
        private int holes;
        private final byte[] cdata = new byte[512]; // map of any 'holes'

        public int getCdata(final int idx) {
            return cdata[idx];
        }

        public int getCount() {
            return count;
        }

        public int getHoles() {
            return holes;
        }

        public int getIno() {
            return ino;
        }

        public DumpArchiveConstants.SEGMENT_TYPE getType() {
            return type;
        }

        public int getVolume() {
            return volume;
        }

        void setIno(final int ino) {
            this.ino = ino;
        }
    }

    /**
     * Enumerates types.
     */
    public enum TYPE {

        /**
         * WHITEOUT with code 14.
         */
        WHITEOUT(14),

        /**
         * SOCKET with code 12.
         */
        SOCKET(12),

        /**
         * LINK with code 10.
         */
        LINK(10),

        /**
         * FILE with code 8.
         */
        FILE(8),

        /**
         * BLKDEV with code 6.
         */
        BLKDEV(6),

        /**
         * DIRECTORY with code 4.
         */
        DIRECTORY(4),

        /**
         * CHRDEV with code 2.
         */
        CHRDEV(2),

        /**
         * CHRDEV with code 1.
         */
        FIFO(1),

        /**
         * UNKNOWN with code 15.
         */
        UNKNOWN(15);

        /**
         * Finds a matching enumeration value for the given code.
         *
         * @param code a code.
         * @return a value, never null.
         */
        public static TYPE find(final int code) {
            TYPE type = UNKNOWN;
            for (final TYPE t : values()) {
                if (code == t.code) {
                    type = t;
                }
            }
            return type;
        }

        private final int code;

        TYPE(final int code) {
            this.code = code;
        }
    }

    /**
     * Populate the dump archive entry and tape segment header with the contents of the buffer.
     *
     * @param buffer buffer to read content from
     */
    static DumpArchiveEntry parse(final byte[] buffer) {
        final DumpArchiveEntry entry = new DumpArchiveEntry();
        final TapeSegmentHeader header = entry.header;

        header.type = DumpArchiveConstants.SEGMENT_TYPE.find(DumpArchiveUtil.convert32(buffer, 0));

        // header.dumpDate = new Date(1000L * DumpArchiveUtil.convert32(buffer, 4));
        // header.previousDumpDate = new Date(1000L * DumpArchiveUtil.convert32(
        // buffer, 8));
        header.volume = DumpArchiveUtil.convert32(buffer, 12);
        // header.tapea = DumpArchiveUtil.convert32(buffer, 16);
        entry.ino = header.ino = DumpArchiveUtil.convert32(buffer, 20);

        // header.magic = DumpArchiveUtil.convert32(buffer, 24);
        // header.checksum = DumpArchiveUtil.convert32(buffer, 28);
        final int m = DumpArchiveUtil.convert16(buffer, 32);

        // determine the type of the file.
        entry.setType(TYPE.find(m >> 12 & 0x0F));

        // determine the standard permissions
        entry.setMode(m);

        entry.nlink = DumpArchiveUtil.convert16(buffer, 34);
        // inumber, oldids?
        entry.setSize(DumpArchiveUtil.convert64(buffer, 40));

        long t = 1000L * DumpArchiveUtil.convert32(buffer, 48) + DumpArchiveUtil.convert32(buffer, 52) / 1000;
        entry.setAccessTime(new Date(t));
        t = 1000L * DumpArchiveUtil.convert32(buffer, 56) + DumpArchiveUtil.convert32(buffer, 60) / 1000;
        entry.setLastModifiedDate(new Date(t));
        t = 1000L * DumpArchiveUtil.convert32(buffer, 64) + DumpArchiveUtil.convert32(buffer, 68) / 1000;
        entry.ctime = t;

        // db: 72-119 - direct blocks
        // id: 120-131 - indirect blocks
        // entry.flags = DumpArchiveUtil.convert32(buffer, 132);
        // entry.blocks = DumpArchiveUtil.convert32(buffer, 136);
        entry.generation = DumpArchiveUtil.convert32(buffer, 140);
        entry.setUserId(DumpArchiveUtil.convert32(buffer, 144));
        entry.setGroupId(DumpArchiveUtil.convert32(buffer, 148));
        // two 32-bit spare values.
        header.count = DumpArchiveUtil.convert32(buffer, 160);

        header.holes = 0;

        for (int i = 0; i < 512 && i < header.count; i++) {
            if (buffer[164 + i] == 0) {
                header.holes++;
            }
        }

        System.arraycopy(buffer, 164, header.cdata, 0, 512);

        entry.volume = header.getVolume();

        // entry.isSummaryOnly = false;
        return entry;
    }

    private String name;
    private TYPE type = TYPE.UNKNOWN;
    private int mode;
    private Set<PERMISSION> permissions = Collections.emptySet();
    private long size;

    private long atime;

    private long mtime;
    private int uid;
    private int gid;

    /**
     * Currently unused
     */
    private final DumpArchiveSummary summary = null;

    /**
     * This value is available from the standard index.
     */
    private final TapeSegmentHeader header = new TapeSegmentHeader();
    private String simpleName;
    private String originalName;

    /**
     * This value is available from the QFA index.
     */
    private int volume;
    private long offset;
    private int ino;

    private int nlink;

    private long ctime;

    private int generation;

    private boolean isDeleted;

    /**
     * Constructs a default instance.
     */
    public DumpArchiveEntry() {
    }

    /**
     * Constructs a new instance with only names.
     *
     * @param name       path name
     * @param simpleName actual file name.
     */
    public DumpArchiveEntry(final String name, final String simpleName) {
        setName(name);
        this.simpleName = simpleName;
    }

    /**
     * Constructs a new instance with name, inode and type.
     *
     * @param name       the name
     * @param simpleName the simple name
     * @param ino        the ino
     * @param type       the type
     */
    protected DumpArchiveEntry(final String name, final String simpleName, final int ino, final TYPE type) {
        setType(type);
        setName(name);
        this.simpleName = simpleName;
        this.ino = ino;
        this.offset = 0;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == this) {
            return true;
        }
        if (o == null || !o.getClass().equals(getClass())) {
            return false;
        }

        final DumpArchiveEntry rhs = (DumpArchiveEntry) o;

        if (ino != rhs.ino) {
            return false;
        }

        // summary is always null right now, but this may change some day
        if (summary == null && rhs.summary != null // NOSONAR
                || summary != null && !summary.equals(rhs.summary)) { // NOSONAR
            return false;
        }

        return true;
    }

    /**
     * Returns the time the file was last accessed.
     *
     * @return the access time
     */
    public Date getAccessTime() {
        return new Date(atime);
    }

    /**
     * Gets file creation time.
     *
     * @return the creation time
     */
    public Date getCreationTime() {
        return new Date(ctime);
    }

    /**
     * Returns the size of the entry as read from the archive.
     */
    long getEntrySize() {
        return size;
    }

    /**
     * Gets the generation of the file.
     *
     * @return the generation
     */
    public int getGeneration() {
        return generation;
    }

    /**
     * Gets the group id
     *
     * @return the group id
     */
    public int getGroupId() {
        return gid;
    }

    /**
     * Gets the number of records in this segment.
     *
     * @return the number of records
     */
    public int getHeaderCount() {
        return header.getCount();
    }

    /**
     * Gets the number of sparse records in this segment.
     *
     * @return the number of sparse records
     */
    public int getHeaderHoles() {
        return header.getHoles();
    }

    /**
     * Gets the type of the tape segment header.
     *
     * @return the segment header
     */
    public DumpArchiveConstants.SEGMENT_TYPE getHeaderType() {
        return header.getType();
    }

    /**
     * Returns the ino of the entry.
     *
     * @return the ino
     */
    public int getIno() {
        return header.getIno();
    }

    /**
     * The last modified date.
     *
     * @return the last modified date
     */
    @Override
    public Date getLastModifiedDate() {
        return new Date(mtime);
    }

    /**
     * Gets the access permissions on the entry.
     *
     * @return the access permissions
     */
    public int getMode() {
        return mode;
    }

    /**
     * Returns the name of the entry.
     *
     * <p>
     * This method returns the raw name as it is stored inside of the archive.
     * </p>
     *
     * @return the name of the entry.
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * Gets the number of hard links to the entry.
     *
     * @return the number of hard links
     */
    public int getNlink() {
        return nlink;
    }

    /**
     * Gets the offset within the archive
     *
     * @return the offset
     */
    public long getOffset() {
        return offset;
    }

    /**
     * Returns the unmodified name of the entry.
     *
     * @return the name of the entry.
     */
    String getOriginalName() {
        return originalName;
    }

    /**
     * Returns the permissions on the entry.
     *
     * @return the permissions
     */
    public Set<PERMISSION> getPermissions() {
        return permissions;
    }

    /**
     * Returns the path of the entry.
     *
     * @return the path of the entry.
     */
    public String getSimpleName() {
        return simpleName;
    }

    /**
     * Returns the size of the entry.
     *
     * @return the size
     */
    @Override
    public long getSize() {
        return isDirectory() ? SIZE_UNKNOWN : size;
    }

    /**
     * Gets the type of the entry.
     *
     * @return the type
     */
    public TYPE getType() {
        return type;
    }

    /**
     * Gets the user id.
     *
     * @return the user id
     */
    public int getUserId() {
        return uid;
    }

    /**
     * Gets the tape volume where this file is located.
     *
     * @return the volume
     */
    public int getVolume() {
        return volume;
    }

    @Override
    public int hashCode() {
        return ino;
    }

    /**
     * Tests whether this is a block device.
     *
     * @return whether this is a block device.
     */
    public boolean isBlkDev() {
        return type == TYPE.BLKDEV;
    }

    /**
     * Tests whether this is a character device.
     *
     * @return whether this is a character device
     */
    public boolean isChrDev() {
        return type == TYPE.CHRDEV;
    }

    /**
     * Tests whether this file been deleted.
     * For valid on incremental dumps.
     *
     * @return whether the file has been deleted.
     */
    public boolean isDeleted() {
        return isDeleted;
    }

    /**
     * Tests whether this is a directory.
     *
     * @return whether this is a directory
     */
    @Override
    public boolean isDirectory() {
        return type == TYPE.DIRECTORY;
    }

    /**
     * Tests whether whether this is a fifo/pipe.
     *
     * @return whether this is a fifo/pipe.
     */
    public boolean isFifo() {
        return type == TYPE.FIFO;
    }

    /**
     * Tests whether this is a regular file.
     *
     * @return whether this is a regular file.
     */
    public boolean isFile() {
        return type == TYPE.FILE;
    }

    /**
     * Tests whether this is a socket.
     *
     * @return whether this is a socket.
     */
    public boolean isSocket() {
        return type == TYPE.SOCKET;
    }

    /**
     * Tests whether this is a sparse record.
     *
     * @param idx index of the record to check.
     * @return whether this is a sparse record.
     */
    public boolean isSparseRecord(final int idx) {
        return (header.getCdata(idx) & 0x01) == 0;
    }

    /**
     * Sets the time the file was last accessed.
     *
     * @param atime the access time
     */
    public void setAccessTime(final Date atime) {
        this.atime = atime.getTime();
    }

    /**
     * Sets the file creation time.
     *
     * @param ctime the creation time
     */
    public void setCreationTime(final Date ctime) {
        this.ctime = ctime.getTime();
    }

    /**
     * Sets whether this file has been deleted.
     *
     * @param isDeleted whether the file has been deleted
     */
    public void setDeleted(final boolean isDeleted) {
        this.isDeleted = isDeleted;
    }

    /**
     * Sets the generation of the file.
     *
     * @param generation the generation
     */
    public void setGeneration(final int generation) {
        this.generation = generation;
    }

    /**
     * Sets the group id.
     *
     * @param gid the group id
     */
    public void setGroupId(final int gid) {
        this.gid = gid;
    }

    /**
     * Sets the time the file was last modified.
     *
     * @param mtime the last modified time
     */
    public void setLastModifiedDate(final Date mtime) {
        this.mtime = mtime.getTime();
    }

    /**
     * Sets the access permissions on the entry.
     *
     * @param mode the access permissions
     */
    public void setMode(final int mode) {
        this.mode = mode & 07777;
        this.permissions = PERMISSION.find(mode);
    }

    /**
     * Sets the name of the entry.
     *
     * @param name the name
     */
    public final void setName(String name) {
        this.originalName = name;
        if (name != null) {
            if (isDirectory() && !name.endsWith("/")) {
                name += "/";
            }
            if (name.startsWith("./")) {
                name = name.substring(2);
            }
        }
        this.name = name;
    }

    /**
     * Sets the number of hard links.
     *
     * @param nlink the number of hard links
     */
    public void setNlink(final int nlink) {
        this.nlink = nlink;
    }

    /**
     * Sets the offset within the archive.
     *
     * @param offset the offset
     */
    public void setOffset(final long offset) {
        this.offset = offset;
    }

    /**
     * Sets the path of the entry.
     *
     * @param simpleName the simple name
     */
    protected void setSimpleName(final String simpleName) {
        this.simpleName = simpleName;
    }

    /**
     * Sets the size of the entry.
     *
     * @param size the size
     */
    public void setSize(final long size) {
        this.size = size;
    }

    /**
     * Sets the type of the entry.
     *
     * @param type the type
     */
    public void setType(final TYPE type) {
        this.type = type;
    }

    /**
     * Sets the user id.
     *
     * @param uid the user id
     */
    public void setUserId(final int uid) {
        this.uid = uid;
    }

    /**
     * Sets the tape volume.
     *
     * @param volume the volume
     */
    public void setVolume(final int volume) {
        this.volume = volume;
    }

    @Override
    public String toString() {
        return getName();
    }

    /**
     * Update entry with information from next tape segment header.
     */
    void update(final byte[] buffer) {
        header.volume = DumpArchiveUtil.convert32(buffer, 16);
        header.count = DumpArchiveUtil.convert32(buffer, 160);

        header.holes = 0;

        for (int i = 0; i < 512 && i < header.count; i++) {
            if (buffer[164 + i] == 0) {
                header.holes++;
            }
        }

        System.arraycopy(buffer, 164, header.cdata, 0, 512);
    }
}
