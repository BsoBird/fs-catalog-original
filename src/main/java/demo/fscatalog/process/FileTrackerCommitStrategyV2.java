package demo.fscatalog.process;

import demo.fscatalog.io.FileIO;
import demo.fscatalog.io.entity.FileEntity;
import demo.fscatalog.io.util.UniIdUtils;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * This is a very aggressive submission strategy. We expect only one client to perform a two-phase commit for the same version. If during the submission process, we detect any submission information (files) from other clients under the same version, all clients immediately fail and exit, then proceed to the next submission attempt.
 *
 * In theory, as long as the file system supports atomic writes, this strategy can be applied to any file system.
 *
 * A drawback of this strategy is that if a client fails in its previous submission and does not generate a HINT file, the next submission from other clients will still fail. Therefore, other clients need to complete the HINT file generated by the previous submission in their next submission. However, after generating the HINT file, the client must still fail and exit.
 */
public class FileTrackerCommitStrategyV2 implements CommitStrategy{

    public static final String COMMIT_HINT = "COMMIT-HINT.TXT";
    public static final String EXPIRED_HINT = "EXPIRED-HINT.TXT";
    public static final String PRE_COMMIT_PREFIX = "PRE_COMMIT-";
    // just demo,no config
    private static final Integer maxSaveNum = 2;
    private static final Integer maxArchiveSize = 100;
    private static final Integer archiveBatchCleanMaxSize = 20;
    private static final long TTL_PRE_COMMIT = 30*1000L;
    // just demo,no config
    private static final long CLEAN_TTL = 60L * 1000 * 10;

    @Override
    public void commit(FileIO fileIO, URI rootPath) throws Exception {
        URI trackerDir = rootPath.resolve("tracker/");
        URI commitDirRoot = rootPath.resolve("commit/");
        URI archiveDir = rootPath.resolve("archive/");

        fileIO.createDirectory(trackerDir);
        fileIO.createDirectory(commitDirRoot);
        fileIO.createDirectory(archiveDir);

        List<FileEntity> trackerList = fileIO.listAllFiles(trackerDir,false);
        long maxCommitVersion = trackerList
                .stream()
                .map(x->Long.parseLong(x.getFileName().split("\\.")[0]))
                .max(Long::compareTo)
                .orElse(0L);

        URI trackerFile = trackerDir.resolve(maxCommitVersion+".txt");
        URI commitRootDirWithTracker = commitDirRoot.resolve(maxCommitVersion+"/");

        URI commitSubTrackerDir = commitRootDirWithTracker.resolve("sub-tracker/");
        URI commitSubHintDir = commitRootDirWithTracker.resolve("sub-hint/");
        URI commitSubHintFile = commitSubHintDir.resolve(COMMIT_HINT);


        //TODO: This only contains the submission logic, not the reading logic. For the reading logic,
        // we first attempt to get the current maximum version (maxCommitVersion) from the tracker.
        // Then we check if a commitHintFile exists under this version. If the commitHintFile is found not to exist,
        // we should decrement maxCommitVersion by 1 and try reading again (we consider that this maximum version
        // hasn't actually completed a valid commit, and the latest valid version should be the decremented one).
        // If we still can't find the commitHintFile after decrementing by 1, we consider that this table might be
        // corrupted and should throw an error, then do nothing. (Of course, in extreme cases, high concurrency
        // submissions could also cause this issue, but we won't consider that impact here for now—false positives
        // are acceptable.)
        if(fileIO.exists(commitSubHintFile)){
            maxCommitVersion++;
            // Scroll forward once.
            trackerFile = trackerDir.resolve(maxCommitVersion+".txt");
            commitRootDirWithTracker = commitDirRoot.resolve(maxCommitVersion+"/");

            commitSubTrackerDir = commitRootDirWithTracker.resolve("sub-tracker/");
            commitSubHintDir = commitRootDirWithTracker.resolve("sub-hint/");
            commitSubHintFile = commitSubHintDir.resolve(COMMIT_HINT);
        }


        if(!fileIO.exists(trackerFile)){
            fileIO.writeFileWithoutGuarantees(trackerFile,maxCommitVersion+"");
        }

        fileIO.createDirectory(commitRootDirWithTracker);
        fileIO.createDirectory(commitSubTrackerDir);
        fileIO.createDirectory(commitSubHintDir);

        List<FileEntity> subTrackerList =fileIO.listAllFiles(commitSubTrackerDir,false);
        long subCommitVersion = subTrackerList
                .stream()
                .map(x->Long.parseLong(x.getFileName().split("\\.")[0]))
                .max(Long::compareTo)
                .orElse(0L);

        URI subTrackerFile = commitSubTrackerDir.resolve(subCommitVersion+".txt");
        URI commitDetailDir = commitRootDirWithTracker.resolve(subCommitVersion+"/");
        URI commitDetailExpireHint = commitDetailDir.resolve(EXPIRED_HINT);

        if(fileIO.exists(commitDetailExpireHint)){
            subCommitVersion++;
            subTrackerFile = commitSubTrackerDir.resolve(subCommitVersion+".txt");
            commitDetailDir = commitRootDirWithTracker.resolve(subCommitVersion+"/");
            commitDetailExpireHint = commitDetailDir.resolve(EXPIRED_HINT);
        }

        if(!fileIO.exists(subTrackerFile)){
            fileIO.writeFileWithoutGuarantees(subTrackerFile,subCommitVersion+"");
        }
        fileIO.createDirectory(commitDetailDir);
        List<FileEntity> commitDetails = fileIO.listAllFiles(commitDetailDir,false);
        if(!commitDetails.isEmpty()){
            Map<String,List<FileEntity>> groupedCommitInfo = getCommitInfoByCommitGroup(commitDetails);
            List<List<FileEntity>> counter = groupedCommitInfo.values().stream().filter(x->x.size()==1).collect(Collectors.toList());

            // If we find multiple files starting with PRE-COMMIT, it means multiple clients are submitting simultaneously.
            // This submission will definitely fail. After writing EXPIRE, we will roll back.
            if(counter.size()==groupedCommitInfo.size() && groupedCommitInfo.size()>1){
                fileIO.writeFileWithoutGuarantees(commitDetailExpireHint,"EXPIRED!");
                throw new ConcurrentModificationException("ConcurrentModificationException!");
            }

            long latestCommitTimestamp = commitDetails.stream().map(FileEntity::getLastModified).max(Long::compareTo).orElse(Long.MAX_VALUE);
            String commitFileName = groupedCommitInfo.keySet().stream().findAny().orElse(null);
            //If a client completes the two-phase commit but fails to write the VERSION-HINT,
            // and if only one client has written the two-phase commit file, then supplementally write the VERSION-HINT once.
            // Otherwise, write an EXPIRE marker and roll over to the next commit space.
            if(System.currentTimeMillis() - latestCommitTimestamp > TTL_PRE_COMMIT && !fileIO.exists(commitSubHintFile)){
                if(groupedCommitInfo.size()==1 && groupedCommitInfo.get(commitFileName).size()==2){
                    // If there is only one group, it may indicate that the previous client encountered an IO exception and failed.
                    // Since there is no concurrency issue,
                    // we will supplement the HINT information once. Then, exit with a failure.
                    String hintInfo = commitFileName+"@"+subCommitVersion;
                    fileIO.writeFileWithoutGuarantees(commitSubHintFile,hintInfo);
                    URI debugFile = commitSubHintDir.resolve(commitFileName);
                    // Debug which clients successfully submitted in the end.
                    // If we find that the number of debug files in the commit folder is greater than 1, then there is an issue.
                    fileIO.writeFileWithoutGuarantees(debugFile,commitFileName);
                }else{
                    fileIO.writeFileWithoutGuarantees(commitDetailExpireHint,"EXPIRED!");
                }
            }
            throw new ConcurrentModificationException("ConcurrentModificationException!");
        }
        String commitFileName = UniIdUtils.getUniId()+".txt";
        String preCommitFileName = PRE_COMMIT_PREFIX+commitFileName;
        URI preCommitFile = commitDetailDir.resolve(preCommitFileName);
        URI commitFile = commitDetailDir.resolve(commitFileName);
        fileIO.writeFileWithoutGuarantees(preCommitFile,preCommitFileName);
        commitDetails = fileIO.listAllFiles(commitDetailDir,false)
                .stream()
                .filter(x->!x.getFileName().equals(preCommitFileName))
                .collect(Collectors.toList());
        if(!commitDetails.isEmpty()){
//            long latestCommitTimestamp = commitDetails.stream().map(FileEntity::getLastModified).max(Long::compareTo).orElse(Long.MAX_VALUE);
//            if(System.currentTimeMillis() - latestCommitTimestamp > TTL_PRE_COMMIT){
//                fileIO.writeFile(commitDetailExpireHint,"EXPIRED!",false);
//            }
            throw new ConcurrentModificationException("ConcurrentModificationException!");
        }
        fileIO.writeFileWithoutGuarantees(commitFile,commitFileName);
        commitDetails = fileIO.listAllFiles(commitDetailDir,false)
                .stream()
                .filter(x->!x.getFileName().equals(preCommitFileName))
                .filter(x->!x.getFileName().equals(commitFileName))
                .collect(Collectors.toList());
        if(!commitDetails.isEmpty()){
//            long latestCommitTimestamp = commitDetails.stream().map(FileEntity::getLastModified).max(Long::compareTo).orElse(Long.MAX_VALUE);
//            if(System.currentTimeMillis() - latestCommitTimestamp > TTL_PRE_COMMIT){
//                fileIO.writeFile(commitDetailExpireHint,"EXPIRED!",false);
//            }
            throw new ConcurrentModificationException("ConcurrentModificationException!");
        }
        String hintInfo = commitFileName+"@"+subCommitVersion;
        fileIO.writeFileWithoutGuarantees(commitSubHintFile,hintInfo);
        URI debugFile = commitSubHintDir.resolve(commitFileName);
        // debug一下哪些客户端最终成功提交了,如果我们发现commit文件夹中debug文件数量大于1,则存在问题
        fileIO.writeFileWithoutGuarantees(debugFile,commitFileName);

        trackerList = fileIO.listAllFiles(trackerDir,false);

        moveTooOldTracker2Archive(fileIO,trackerList,maxCommitVersion,archiveDir,trackerDir);
        cleanTooOldCommit(fileIO,archiveDir,commitDirRoot);
    }

    private Map<String,List<FileEntity>> getCommitInfoByCommitGroup(List<FileEntity> fileEntityList){
        Map<String,List<FileEntity>> result = new HashMap<>();
        fileEntityList.stream()
                .filter(x->!EXPIRED_HINT.equals(x.getFileName()))
                .forEach(x->{
                    String key = x.getFileName();
                    if(key!=null){
                        if(key.startsWith(PRE_COMMIT_PREFIX)){
                            key = key.substring(PRE_COMMIT_PREFIX.length());
                        }
                        result.computeIfAbsent(key, k->new ArrayList<>()).add(x);
                    }
                });
        return result;
    }


    private void moveTooOldTracker2Archive(FileIO fileIO, List<FileEntity> trackerList, long maxVersionAfterCommit, URI archiveDir, URI trackerDir) throws IOException {
        //TODO: Minor issue: The client may need to write to the archive once regardless of whether the submission
        // succeeds or fails, because outdated submissions always need to be cleaned up.
        // In extreme cases, if submissions keep failing, the outdated submissions won't be able to be cleaned up.
        List<FileEntity> needMove2Archive = trackerList.stream()
                .filter(x->{
                    String name = x.getFileName();
                    String versionStr = name.split("\\.")[0];
                    long fileVersion = Long.parseLong(versionStr);
                    return maxVersionAfterCommit -fileVersion > maxSaveNum;
                }).collect(Collectors.toList());

        for (FileEntity archiveFile : needMove2Archive) {
            String expireTimeStamp = String.valueOf(System.currentTimeMillis()+CLEAN_TTL);
            //todo: Add a timestamp to the file name so key information like expiration time can be extracted directly
            // from the filename, mainly to save IO by avoiding an extra read. However, this approach has an issue—if
            // multiple clients execute move2Archive simultaneously, since their execution times may differ,
            // the same tracker could generate multiple archive records. This might slightly interfere with cleanup.
            // For now, we'll leave this issue unaddressed.
            String archiveFileName = archiveFile.getFileName()+"@"+expireTimeStamp;
            URI dropTracker = trackerDir.resolve(archiveFile.getFileName());
            URI archiveEntity = archiveDir.resolve(archiveFileName);
            if(!fileIO.exists(archiveEntity)){
                fileIO.writeFileWithoutGuarantees(archiveEntity,expireTimeStamp);
            }
            fileIO.delete(dropTracker,false);
        }
    }

    private void cleanTooOldCommit(FileIO fileIO, URI archiveDir, URI commitDirRoot) throws IOException {
        List<FileEntity> archiveList = fileIO.listAllFiles(archiveDir,false);
        archiveList.sort(Comparator.comparing(x-> Long.parseLong(x.getFileName().split("\\.")[0])));
        int maxCleanTimes = Math.min(1,archiveList.size());
        if(archiveList.size()>maxArchiveSize){
            //In a multithreaded scenario, deleting items one by one may not keep up with the write speed. That's why batch processing is implemented here.
            maxCleanTimes = Math.min(archiveBatchCleanMaxSize,archiveList.size());
        }
        for(int i=0;i<maxCleanTimes;i++){
            FileEntity cleanFile = archiveList.get(i);
            if(cleanFile!=null){
                String fileName = cleanFile.getFileName();
                URI archiveFile = archiveDir.resolve(fileName);
                long expireTimestamp = Long.parseLong(fileName.split("@")[1]);
                if(System.currentTimeMillis()>expireTimestamp){
                    String dropVersion = fileName.split("\\.")[0];
                    URI oldCommitDir = commitDirRoot.resolve(dropVersion+"/");
                    fileIO.delete(oldCommitDir,true);
                    fileIO.delete(archiveFile,false);
                }
            }
        }
    }



}
