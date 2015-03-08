package org.dcache.nfs.vfs;

import org.dcache.chimera.UnixPermission;

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;

import java.util.List;
import java.util.ArrayList;

import javax.security.auth.Subject;
import org.dcache.nfs.v4.NfsIdMapping;
import org.dcache.nfs.v4.xdr.nfsace4;

/**
 * A file system which forwards all its method calls to another file system.
 * Subclasses should override one or more methods to modify the behavior of the
 * backing file system as desired per the
 * <a href="http://en.wikipedia.org/wiki/Decorator_pattern">decorator pattern</a>.
 * @sice 0.10
 */
public class ReadOnlyDirectoryFileSystem implements VirtualFileSystem, AclCheckable{

    private File rootDirectory;

    private final NfsIdMapping _idMapping;

    public ReadOnlyDirectoryFileSystem(NfsIdMapping mapping)
    {
      this._idMapping = mapping;
      rootDirectory = new File(System.getProperty("user.home") + File.separator + "exports");
    }

    public ReadOnlyDirectoryFileSystem(NfsIdMapping mapping, File root)
    {
      this._idMapping = mapping;
      this.rootDirectory = root;
    }

    public void setRootDirectory(File file)
    {
      this.rootDirectory = file;
    }

    @Override
    public int access(Inode inode, int mode) throws IOException {
      //System.out.println("access(" + new String(inode.getFileId()) + ", " + mode + ")");
      return mode;
    }

    @Override
    public Inode create(Inode parent, Stat.Type type, String path, Subject subject, int mode) throws IOException {
      //System.out.println("create()");
      throw new IOException("Not implemented");
    }

    private FsStat calculateStats(File directory)
    {
      File[] files = directory.listFiles();
      long totalFiles = 0;
      long totalSpace = 0;
      for(File file : files)
      {
        if( file.isDirectory() )
        {
          FsStat stat = calculateStats( file );
          totalFiles += stat.getTotalFiles();
          totalSpace = stat.getTotalSpace();
        }
        else
        {
          totalFiles += 1;
          totalSpace += file.length();
        }
      }
      return new FsStat(totalSpace, totalFiles, totalSpace, totalFiles);
    }

    @Override
    public FsStat getFsStat() throws IOException {
      //System.out.println("getFsStat()");
      return calculateStats( rootDirectory );
    }

    @Override
    public Inode getRootInode() throws IOException {
      //System.out.println("getRootInode()");
      return Inode.forFile( File.separator.getBytes() );
    }

    @Override
    public Inode lookup(Inode parent, String path) throws IOException {
      //System.out.println("lookup(" + path + ")");
      String fpath = new String(parent.getFileId()) + File.separator + path;
      //System.out.println("lookup(" + fpath + ")");
      File file = new File(rootDirectory, fpath);
      //System.out.println(file + " " + file.exists()); 
      if( file.exists() )
      {
        return Inode.forFile( fpath.getBytes() );
      }
      throw new IOException("Path does not exist.");
    }

    @Override
    public Inode link(Inode parent, Inode link, String path, Subject subject) throws IOException {
      //System.out.println("link()");
      throw new IOException("Not implemented");
    }

    private Stat statForFile(File file)
    {
      Stat result = new Stat();
      result.setUid( 1000 );
      result.setGid( 1000 );
      result.setNlink( 0 );
      result.setATime( file.lastModified() );
      result.setMTime( file.lastModified() );
      result.setCTime( file.lastModified() );
      result.setFileid( file.hashCode() );
      result.setSize( file.length() );
      result.setRdev( 0 );
      result.setDev( 0 );
      result.setIno( 4096 );
      result.setGeneration( 0 );
 
      if( file.isDirectory() )
      {
        result.setMode( Stat.S_IFDIR | UnixPermission.S_IRUSR |
                                       UnixPermission.S_IXUSR |
                                       UnixPermission.S_IRGRP |
                                       UnixPermission.S_IXGRP |
                                       UnixPermission.S_IROTH |
                                       UnixPermission.S_IXOTH );
      }
      else
      {
        result.setMode( Stat.S_IFREG | UnixPermission.S_IRUSR |
                                       UnixPermission.S_IRGRP |
                                       UnixPermission.S_IROTH );
      }
      return result;
    }
 
    @Override
    public List<DirectoryEntry> list(Inode inode) throws IOException {
      List<DirectoryEntry> result = new ArrayList<DirectoryEntry>();
      String path = new String(inode.getFileId());
      File file = new File(rootDirectory, path);
      //System.out.println("list(" + path + ")");
      if( file.exists() && file.isDirectory() )
      {
        File[] files = file.listFiles();
        for(File cfile : files)
        {
          //System.out.println("Found: " + cfile );
          String fPath = path + File.separator + cfile.getName();
          DirectoryEntry entry = new DirectoryEntry(
              cfile.getName(), Inode.forFile(fPath.getBytes()), statForFile(cfile));
          result.add( entry );
        }
      }
      return result;
    }

    @Override
    public Inode mkdir(Inode parent, String path, Subject subject, int mode) throws IOException {
      //System.out.println("mkdir()");
      throw new IOException("Not implemented");
    }

    @Override
    public boolean move(Inode src, String oldName, Inode dest, String newName) throws IOException {
      //System.out.println("move()");
      throw new IOException("Not implemented");
    }

    @Override
    public Inode parentOf(Inode inode) throws IOException {
      //System.out.println("parentOf()");
      throw new IOException("Not implemented");
    }

    @Override
    public int read(Inode inode, byte[] data, long offset, int count) throws IOException {
      int read = -1;
      String path = new String(inode.getFileId());
      File file = new File(rootDirectory, path);
      if( file.exists() )
      {
        FileInputStream input = new FileInputStream(file);
        read = input.read(data, 0, count);
        input.close(); 
      }
      return read; 
    }

    @Override
    public String readlink(Inode inode) throws IOException {
      //System.out.println("readLink()");
      throw new IOException("Not implemented");
    }

    @Override
    public void remove(Inode parent, String path) throws IOException {
      //System.out.println("remove()");
      throw new IOException("Not implemented");
    }

    @Override
    public Inode symlink(Inode parent, String path, String link, Subject subject, int mode) throws IOException {
      //System.out.println("symlink()");
      throw new IOException("Not implemented");
    }

    @Override
    public WriteResult write(Inode inode, byte[] data, long offset, int count, StabilityLevel stabilityLevel) throws IOException {
      //System.out.println("write()");
      throw new IOException("Not implemented");
    }

    @Override
    public void commit(Inode inode, long offset, int count) throws IOException {
      //System.out.println("commit()");
      throw new IOException("Not implemented");
    }

    @Override
    public Stat getattr(Inode inode) throws IOException {
      //System.out.println("getattr(" + new String(inode.getFileId()) + ")");
      String path = new String(inode.getFileId());
      File file = new File(rootDirectory, path);
      return statForFile( file );
    }

    @Override
    public void setattr(Inode inode, Stat stat) throws IOException {
      //System.out.println("setattr()");
      throw new IOException("Not implemented");
    }

    @Override
    public nfsace4[] getAcl(Inode inode) throws IOException {
      //System.out.println("getAcl()");
      throw new IOException("Not implemented");
    }

    @Override
    public void setAcl(Inode inode, nfsace4[] acl) throws IOException {
      //System.out.println("setAcl()");
      throw new IOException("Not implemented");
    }

    @Override
    public boolean hasIOLayout(Inode inode) throws IOException {
      //System.out.println("hasIOLayout()");
      return true;
    }

    @Override
    public AclCheckable getAclCheckable() {
        return this;
    }

    @Override
    public NfsIdMapping getIdMapper() {
        return _idMapping;
    }

    public Access checkAcl(Subject subject, Inode inode, int access) throws IOException {
      //System.out.println("checkAcl");
      throw new IOException("Not implemented");
    } 
}
