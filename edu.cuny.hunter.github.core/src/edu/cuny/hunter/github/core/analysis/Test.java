package edu.cuny.hunter.github.core.analysis;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.HunkHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

@SuppressWarnings("restriction")
public class Test {

	public static void main(String[] args) throws IOException, GitAPIException {

		Repository repo = new FileRepository("C:\\Users\\tangy\\eclipse-workspace\\Java-8-Stream-Refactoring\\.git");

		Git git = new Git(repo);

		Iterable<RevCommit> log = git.log().call();
		RevCommit previousCommit = null;

		for (RevCommit commit : log) {

			if (previousCommit != null) {

				System.out.println("LogCommit: " + commit);
				String logMessage = commit.getFullMessage();
				System.out.println("LogMessage: " + logMessage);

				AbstractTreeIterator oldTreeIterator = getCanonicalTreeParser(previousCommit, repo);
				AbstractTreeIterator newTreeIterator = getCanonicalTreeParser(commit, repo);

				// each diff entry is corresponding to a file
				final List<DiffEntry> diffs = git.diff().setOldTree(oldTreeIterator).setNewTree(newTreeIterator).call();

				OutputStream outputStream = new ByteArrayOutputStream();
				try (DiffFormatter formatter = new DiffFormatter(outputStream)) {
					formatter.setRepository(repo);
					formatter.scan(oldTreeIterator, newTreeIterator);

					for (DiffEntry diffEntry : diffs) {

						FileHeader fileHeader = formatter.toFileHeader(diffEntry);

						// add a file
						if (diffEntry.getChangeType().name().equals("ADD")) {
							System.out.println("ADD: " + diffEntry.getNewPath());
							System.out.println();
						} else // delete a file
						if (diffEntry.getChangeType().name().equals("DELETE")) {
							System.out.println("DELETE: ");
							System.out.println(diffEntry.getOldPath());
							System.out.println();

						} // modify a file
						if (diffEntry.getChangeType().name().equals("MODIFY")) {
							System.out.println("----------------------------------");
							System.out.println("MODIFY: " + diffEntry.getNewPath());
							List<? extends HunkHeader> hunks = fileHeader.getHunks();
							for (HunkHeader hunk : hunks) {
								EditList editList = hunk.toEditList();
								if (!editList.isEmpty()) {
									editList.forEach(edit -> {
										System.out.println("Revision A: start at " + edit.getBeginA() + ", end at "
												+ edit.getEndA());
										System.out.println("Revision B: start at " + edit.getBeginB() + ", end at "
												+ edit.getEndB());
									});
								}

							}
							// Get the file for revision A
							getHistoricalFile(commit, repo, diffEntry.getOldPath());

							System.out.println();
						}

					}
				}

			}
			System.out.println("#######################################");
			previousCommit = commit;
		}

		git.close();
	}

	private static AbstractTreeIterator getCanonicalTreeParser(ObjectId commitId, Repository repo) throws IOException {
		try (RevWalk walk = new RevWalk(repo)) {
			RevCommit commit = walk.parseCommit(commitId);
			ObjectId treeId = commit.getTree().getId();
			try (ObjectReader reader = repo.newObjectReader()) {
				return new CanonicalTreeParser(null, reader, treeId);
			}
		}
	}

	/**
	 * Given the commit, repository and the path of the file, get the file.
	 */
	private static void getHistoricalFile(RevCommit commit, Repository repo, String path)
			throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException, IOException {
		RevTree tree = commit.getTree();
		@SuppressWarnings("resource")
		TreeWalk treeWalk = new TreeWalk(repo);
		treeWalk.addTree(tree);
		treeWalk.setRecursive(true);
		treeWalk.setFilter(PathFilter.create(path));
		if (!treeWalk.next()) {
			return;
		}
		ObjectId objectId = treeWalk.getObjectId(0);
		ObjectLoader loader = repo.open(objectId);

		copyToFile(loader, path);
	}

	private static void copyToFile(ObjectLoader loader, String path) throws IOException {
		// Only need to consider java files here.
		if (!path.contains(".java"))
			return;

		// I would like to move the files but the file name should keep
		int index = path.lastIndexOf("/");
		String fileName;
		if (index != -1)
			fileName = path.substring(index + 1);
		else
			fileName = path;

		try {
			// ALL files are moved into a new directory
			File file = new File("Git/" + fileName);
			if (!file.exists()) {
				if (!file.getParentFile().exists())
					file.getParentFile().mkdir();

				file.createNewFile();
			} else {
				System.out.println("New file path: " + file.getAbsolutePath());
				FileOutputStream fileOutputStream = new FileOutputStream(file.getAbsolutePath(), false);
				loader.copyTo(fileOutputStream);
				fileOutputStream.close();

				// parse the java file
				String fileContent = new BufferedReader(new InputStreamReader(loader.openStream())).lines()
						.collect(Collectors.joining("\n"));
				if (!fileContent.isEmpty())
					parseJavaFile(file, fileContent);
			}

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Parse a Java file
	 */
	private static void parseJavaFile(File file, String fileContent) throws IOException {
		ASTParser parser = ASTParser.newParser(AST.JLS8);
		parser.setResolveBindings(true);
		parser.setSource(fileContent.toCharArray());

		final CompilationUnit cu = (CompilationUnit) parser.createAST(new NullProgressMonitor());
		System.out.println("Parse a Java file! " + cu.getNodeType());
	}

}
