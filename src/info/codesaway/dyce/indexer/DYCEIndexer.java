package info.codesaway.dyce.indexer;

import static info.codesaway.util.indexer.IndexerUtilities.FULL_PATH_FIELD;
import static info.codesaway.util.indexer.IndexerUtilities.PATHNAME_FIELD;
import static info.codesaway.util.indexer.IndexerUtilities.PATH_FIELD;
import static info.codesaway.util.indexer.IndexerUtilities.createMetaDocument;
import static java.util.stream.Collectors.toSet;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import info.codesaway.dyce.Activator;
import info.codesaway.dyce.DYCESettings;
import info.codesaway.dyce.DYCEView;
import info.codesaway.dyce.jobs.DYCEIndexJob;
import info.codesaway.dyce.util.DYCEUtilities;
import info.codesaway.util.indexer.DocumentInfo;
import info.codesaway.util.indexer.LuceneStep;
import info.codesaway.util.indexer.PathWithLastModified;
import info.codesaway.util.indexer.PathWithTerm;

public class DYCEIndexer {
	// private static final List<Path> DIRECTORIES =
	// Arrays.asList(Activator.WORKSPACE_PATH);
	private static SearcherManager SEARCHER_MANAGER;

	public static final Path INDEX_PATH = Activator.STATE_LOCATION.resolve("WorkspaceIndex");

	private static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");

	/**
	 * Match metadocuments which store the document_version
	 */
	private static final Term METADOCUMENT_TERM = new Term("metadocument", "meta");

	// Index JDK zip
	// https://stackoverflow.com/a/37413531
	// https://docs.oracle.com/javase/8/docs/technotes/guides/io/fsp/zipfilesystemprovider.html
	private static final String jdkZipPathname = "C:\\Java\\jdk8\\src.zip";
	private static final Path jdkZipPath = Paths.get(jdkZipPathname);

	@NonNull
	//	public static final String COMMENT = "comment";

	private static IndexWriter INDEX_WRITER;

	// TODO: make settings
	// Number of files to index at once before committing
	// (especially needed to handle initial indexing when all documents need to
	// be indexed)
	// (this way, once a group of files have been indexed, they can be queried,
	// while the rest of the files are still being indexed)
	// https://stackoverflow.com/questions/32269632/writing-to-lucene-index-one-document-at-a-time-slows-down-over-time
	private static int INDEX_GROUP_COUNT = 100;

	private static int CANCEL_CHECK_COUNT = 200;

	// Number of documents to read on first pass before commiting
	// (keep this number low to see quick results when perform full rebuid of
	// index)
	private static int INITIALLY_READ_COUNT = 10;

	private static final int SECONDS_PER_MINUTE = 60;
	private static final int SECONDS_PER_HOUR = 60 * SECONDS_PER_MINUTE;

	private static IndexWriter createWriter() throws IOException {
		FSDirectory dir = FSDirectory.open(INDEX_PATH);

		Analyzer analyzer = DYCEUtilities.createAnalyzer(LuceneStep.INDEX);

		IndexWriterConfig config = new IndexWriterConfig(analyzer);
		IndexWriter writer = new IndexWriter(dir, config);
		return writer;
	}

	private static IndexWriter getWriter() throws IOException {
		if (INDEX_WRITER != null) {
			return INDEX_WRITER;
		}

		return INDEX_WRITER = createWriter();
	}

	public static void closeWriter() {
		if (INDEX_WRITER != null) {
			try {
				INDEX_WRITER.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
			}
		}
	}

	private static Stream<PathWithLastModified> walkDirectories() {
		// System.out.println("Walk directories: " + directories);

		Stream<PathWithLastModified> result = Stream.empty();

		// Add any external projects (not a child of the workspace path)
		for (IProject project : Activator.WORKSPACE_ROOT.getProjects()) {
			@NonNull
			@SuppressWarnings("null")
			String projectName = project.getName();
			Path projectPath = project.getLocation().toFile().toPath();

			//
			Stream<PathWithLastModified> projectStream;
			try {
				projectStream = Files.walk(projectPath)
						.filter(DYCESettings::shouldIndexFile)
						.map(p -> new PathWithLastModified(projectName, p));
			} catch (IOException e) {
				projectStream = Stream.empty();
			}

			result = Stream.concat(result, projectStream);
		}

		// 11/6/2020 - Index JDK, so can find method names as part of dictation
		// https://www.javadevjournal.com/java/zipping-and-unzipping-in-java/
		// https://www.baeldung.com/java-compress-and-uncompress
		// TODO: pass as setting
		//		String jdkZipPathname = "C:\\Java\\jdk8\\src.zip";
		//		Path jdkZipPath = Paths.get(jdkZipPathname);
		//
		//		// TODO: reference which uses FileSystems (Java 8)
		//		// https://stackoverflow.com/a/37413531
		//		FileSystems.newFile
		//
		//		if (Files.exists(jdkZipPath)) {
		//			// https://stackoverflow.com/a/15667326
		//			try (ZipFile zipFile = new ZipFile(jdkZipPathname)) {
		//				zipFile.stream()
		//						.filter(DYCEIndexer::shouldIndexJDKZipEntry)
		//						.forEachOrdered(e -> System.out.println(e));
		//			} catch (IOException e) {
		//				// Do nothing
		//			}
		//
		//			//			Stream<PathWithLastModified> jdkStream;
		//			//			try {
		//			//				jdkStream = Files.walk(projectPath)
		//			//						.filter(DYCESettings::shouldIndexFile)
		//			//						.map(p -> new PathWithLastModified(projectName, p));
		//			//			} catch (IOException e) {
		//			//				jdkStream = Stream.empty();
		//			//			}
		//			//
		//			//			result = Stream.concat(result, jdkStream);
		//		}

		return result;
	}

	//	private static boolean shouldIndexJDKZipEntry(final ZipEntry entry) {
	private static boolean shouldIndexJDKZipEntry(final Path entry) {
		if (Files.isDirectory(entry)) {
			//		if (entry.isDirectory()) {
			return false;
		}

		String entryName = entry.toString();

		if (!entryName.endsWith(".java")) {
			return false;
		}

		return entryName.startsWith("/java/") || entryName.startsWith("/javax/");
	}

	private static boolean shouldIndex(final PathWithLastModified path, final Map<String, DocumentInfo> documents) {

		DocumentInfo doc = documents.get(path.getPathname());

		if (doc == null) {
			// Newly added file, should index
			return true;
		}

		long lastModifiedValue = doc.getLastModified();
		long fileLastModified = path.getLastModified();

		long documentVersion = doc.getDocumentVersion();

		boolean isModified = fileLastModified != lastModifiedValue
				// TODO: change to have version per indexer (based on file
				// extension)
				|| DYCESettings.DOCUMENT_VERSION != documentVersion;

		return isModified;
	}

	public static void rebuildEntireIndex() {
		if (DYCEView.INSTANCE == null) {
			return;
		}

		DYCEView.cancelIndexing();

		try {
			IndexWriter writer = getWriter();
			// Set all metadocuments to have the same document version
			// Will then index, which should be a different value
			long documentVersion = (DYCESettings.DOCUMENT_VERSION != Long.MIN_VALUE ? Long.MIN_VALUE
					: Long.MAX_VALUE);

			writer.updateNumericDocValue(METADOCUMENT_TERM, "documentVersion", documentVersion);

			commit(writer);
		} catch (IOException e) {
			DYCEView.INSTANCE.setStatus(DYCEView.ERROR_STATUS);
			DYCEView.INSTANCE.setMessage("Cannot rebuild index");
			return;
		}

		DYCEView.INSTANCE.index();
	}

	public static String incrementalRebuildIndex(final DYCEIndexJob dyceIndexJob, final IProgressMonitor monitor)
			throws IOException {
		IndexSearcher searcher = getSearcher();

		LocalDateTime startTime = LocalDateTime.now();

		Map<String, DocumentInfo> documents = new HashMap<>();
		List<Term> deleteDocuments = new ArrayList<>();

		if (searcher != null) {
			// For zips, need to keep track of paths which exist, so don't reindex each time
			// TODO: add ability to indicate that zip was reindexed and don't need to iterate over zip
			// Presume if last modified didn't change, that the zip didn't change (so no need to iterate it)
			// (allows indexing large m2 directories without traversing these cached dependencies multiple times)
			Stream<String> zipPathnamesStream = Stream.empty();
			Set<String> zipPathnames;
			try (FileSystem zipFileSystem = FileSystems.newFileSystem(jdkZipPath, null)) {
				for (Path root : zipFileSystem.getRootDirectories()) {
					Stream<String> jdkStream;
					try {
						jdkStream = Files.walk(root)
								.filter(DYCEIndexer::shouldIndexJDKZipEntry)
								.map(Object::toString);

						zipPathnamesStream = Stream.concat(zipPathnamesStream, jdkStream);
					} catch (IOException e) {
						jdkStream = Stream.empty();
					}
				}

				zipPathnames = zipPathnamesStream.collect(toSet());
			}

			// Determine when last modified file
			// (to allow incremental reindexing)
			for (LeafReaderContext context : searcher.getIndexReader().leaves()) {
				LeafReader leafReader = context.reader();
				Bits liveDocs = leafReader.getLiveDocs();

				BinaryDocValues pathnameDocValues = DocValues.getBinary(leafReader, PATHNAME_FIELD);

				// Will be iterating over both groups of values
				// (uses pathnameDocValues as the main iteration)
				NumericDocValues lastModifiedDocValues = DocValues.getNumeric(leafReader, "fileLastModified");
				int lastModifiedDocId = lastModifiedDocValues.nextDoc();

				// Used to track which version of the code created the document
				// (this way as changes are made to how the documents are
				// indexed, can update the existing documents)
				NumericDocValues documentVersionDocValues = DocValues.getNumeric(leafReader, "documentVersion");
				int documentVersionDocId = documentVersionDocValues.nextDoc();

				while (pathnameDocValues.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
					// https://stackoverflow.com/a/15570353
					int docID = pathnameDocValues.docID();

					// Ignore deleted documents
					if (liveDocs != null && !liveDocs.get(docID)) {
						continue;
					}

					// Find the last modified value corresponding to the
					// document
					// (if none, use 0, so will show as file has been modified)
					// (handles case if each document doesn't have the pathname
					// and fileLastModified doc values)
					// (should never occur, but handling just in case)
					while (lastModifiedDocId < docID) {
						lastModifiedDocId = lastModifiedDocValues.nextDoc();
					}

					while (documentVersionDocId < docID) {
						documentVersionDocId = documentVersionDocValues.nextDoc();
					}

					if (lastModifiedDocId == docID) {
						long lastModified = lastModifiedDocValues.longValue();
						lastModifiedDocId = lastModifiedDocValues.nextDoc();

						long documentVersion = documentVersionDocId == docID ? documentVersionDocValues.longValue() : 0;

						@NonNull
						@SuppressWarnings("null")
						String pathname = pathnameDocValues.binaryValue().utf8ToString();

						// TODO: need to handle files within zip - so don't reindex every time
						//						System.out.println("Pathname: " + pathname);

						// Check if pathname still exists
						// (if not, delete the associated documents)

						//						if (new File(pathname).exists()) {
						if (zipPathnames.contains(pathname) || Files.exists(Paths.get(pathname))) {
							documents.put(pathname, new DocumentInfo(lastModified, documentVersion));
						} else {
							// Path no longer exists
							//							System.out.println("Pathname doesn't exist: " + pathname);
							deleteDocuments.add(PathWithTerm.getTerm(pathname));
						}
					}
				}
			}
		}

		if (monitor.isCanceled()) {
			throw new OperationCanceledException();
		}

		// Determine files need to index and start with the latest modified
		// (since it more likely to want to search more recently modified files)

		@SuppressWarnings("null")
		Stream<PathWithLastModified> stream = walkDirectories()
				.filter(p -> searcher == null ? true : shouldIndex(p, documents))
				// Sort by last modified descending
				// (since want to index recently modified files first
				.sorted(Comparator.comparing(PathWithLastModified::getLastModified).reversed());

		int indexedFiles;

		// TODO: track when the zip was modified, since don't need to reread

		try (FileSystem zipFileSystem = FileSystems.newFileSystem(jdkZipPath, null)) {
			for (Path root : zipFileSystem.getRootDirectories()) {
				Stream<PathWithLastModified> jdkStream;
				try {
					jdkStream = Files.walk(root)
							.filter(DYCEIndexer::shouldIndexJDKZipEntry)
							.map(p -> new PathWithLastModified("[JDK]", p))
							.filter(p -> searcher == null ? true : shouldIndex(p, documents));

					//					Files.walk(root)
					//							.filter(DYCEIndexer::shouldIndexJDKZipEntry)
					//							.forEachOrdered(p -> System.out.println(p));
				} catch (IOException e) {
					jdkStream = Stream.empty();
					e.printStackTrace();
				}

				stream = Stream.concat(stream, jdkStream);
			}

			indexedFiles = index(stream, dyceIndexJob, monitor, false, deleteDocuments.toArray(new Term[0]));
		}

		LocalDateTime endTime = LocalDateTime.now();

		Duration duration = Duration.between(startTime, endTime);

		return createIndexDoneMessage(indexedFiles, duration);
	}

	public static <T extends PathWithLastModified> int index(final Stream<T> paths, final DYCEIndexJob dyceIndexJob,
			final IProgressMonitor monitor, final boolean removeWhenDone, final Term[] deleteDocuments)
			throws IOException {
		// TODO: see how can use submonitor to indicate progress (harder since
		// parallel)
		int initiallyReadCount = INITIALLY_READ_COUNT;

		if (INDEX_GROUP_COUNT < initiallyReadCount) {
			initiallyReadCount = INDEX_GROUP_COUNT;
		}

		// Introduced to make lambda happy
		int initiallyReadCountFinal = initiallyReadCount;

		AtomicInteger filesModifiedCount = new AtomicInteger();

		IndexWriter writer = getWriter();

		if (deleteDocuments.length > 0) {
			writer.deleteDocuments(deleteDocuments);
			writer.commit();

			if (SEARCHER_MANAGER != null) {
				SEARCHER_MANAGER.maybeRefreshBlocking();
			}
		}

		// Iterate in order (so index newly modified files first)
		paths.forEachOrdered(path -> {
			if (path == null) {
				return;
			}

			// Index files

			// First delete any documents, in case file was partially
			// indexed and interrupted
			// (want to start fresh and reindex file)
			try {
				if (!writer.isOpen()) {
					// Handle case such as user deleting the index directory
					// in the middle of indexing
					monitor.setCanceled(true);
					throw new OperationCanceledException();
				}

				writer.deleteDocuments(path.getTerm());

				addDocument(writer, path);

				int count = filesModifiedCount.addAndGet(1);

				if (count % CANCEL_CHECK_COUNT == 0 && monitor.isCanceled()) {
					throw new OperationCanceledException();
				}

				if (count % INDEX_GROUP_COUNT == 0) {
					if (!writer.isOpen()) {
						// Handle case such as user deleting the index
						// directory in the middle of indexing
						monitor.setCanceled(true);
						throw new OperationCanceledException();
					}

					// Commit changes in groups, so can start querying even
					// as rest of files index
					commit(writer);
				}

				if (count == initiallyReadCountFinal) {
					if (count < INDEX_GROUP_COUNT) {
						if (!writer.isOpen()) {
							// Handle case such as user deleting the index
							// directory in the middle of indexing
							monitor.setCanceled(true);
							throw new OperationCanceledException();
						}

						if (monitor.isCanceled()) {
							throw new OperationCanceledException();
						}

						// Commit changes so can then search quickly
						commit(writer);
					}

					dyceIndexJob.setIndexCreated(true);
				}

				if (removeWhenDone) {
					dyceIndexJob.removePath(path);
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		});
		//		}

		int modifiedCount = filesModifiedCount.get();

		if (modifiedCount > 0) {
			commit(writer);
		}

		return modifiedCount;
	}

	// TODO: does nothing currently
	@NonNullByDefault
	private static Map<String, String> getFileRelatedFields(final Path path, final String filename,
			final String extension) {

		switch (extension) {
		// TODO: Need to handle?
		//		case "java":
		//			return CASTLEJavaIndexer.getJavaFileRelatedFields(path, filename);
		default:
			@SuppressWarnings("null")
			@NonNull
			Map<String, String> emptyMap = Collections.emptyMap();

			return emptyMap;

		}
	}

	/**
	 * Index file with each line being a separate document
	 *
	 * @param indexWriter
	 * @param path
	 */
	@NonNullByDefault
	private static void addDocument(final IndexWriter indexWriter, final PathWithLastModified pathWithTerm)
			throws IOException {
		//		File file = pathWithTerm.getFile();
		//		boolean isFile = file.isFile();

		Path path = pathWithTerm.getPath();

		//		if (!isFile) {
		if (Files.isDirectory(path)) {
			// Don't need to index directories
			return;
		}

		// Indicate which project in
		//		Path relative = Activator.WORKSPACE_PATH.relativize(path);

		//		String project = relative.getNameCount() > 0 ? relative.getName(0).toString() : "";

		String project = pathWithTerm.getProject();
		String pathString = path.toString();

		@NonNull
		@SuppressWarnings("null")
		String filename = path.getFileName().toString();

		long fileLastModified = pathWithTerm.getLastModified();

		String extension = getExtension(filename);

		if (extension.equals("java")) {
			addJavaDocument(indexWriter, path);
		}

		// Store information about the file itself
		// (stores last modified so can do incremental reindexing, when files
		// are added or modified)
		// (done last in case indexing was interupted in middle of file)
		// (in this case, the document would not show as indexed in full and
		// would be reindexed)
		// TODO: should also delete documents when corresponding file is deleted
		Document metaDocument = createMetaDocument(pathString, fileLastModified,
				DYCESettings.DOCUMENT_VERSION);

		if (!indexWriter.isOpen()) {
			return;
		}

		indexWriter.addDocument(metaDocument);
	}

	private static void addJavaDocument(final IndexWriter indexWriter, final Path path) throws IOException {
		String pathString = path.toString();
		String filename = path.getFileName().toString();

		ASTParser parser = ASTParser.newParser(AST.JLS13);
		parser.setResolveBindings(true);
		parser.setKind(ASTParser.K_COMPILATION_UNIT);

		parser.setBindingsRecovery(true);
		// TODO: not sure if needed, but setting
		parser.setStatementsRecovery(true);

		boolean alreadySetSource = false;
		try {
			URI uri = path.toUri();

			// Used to prevent CoreException which checking zip files
			// (in this case, wouldn't expect to find in project)
			// org.eclipse.core.runtime.CoreException: No file system is defined for scheme: jar
			if (!Objects.equals(uri.getScheme(), "jar")) {
				IFile[] files = Activator.WORKSPACE_ROOT.findFilesForLocationURI(uri);

				if (files.length > 0) {
					IProject iProject = files[0].getProject();
					// Set source to compilation unit, so can resolve bindings
					parser.setSource(JavaCore.createCompilationUnitFrom(files[0]));
					alreadySetSource = true;
					// https://stackoverflow.com/a/12521662
					if (iProject != null && iProject.hasNature(JavaCore.NATURE_ID)) {
						IJavaProject javaProject = JavaCore.create(iProject);
						// Not sure what this does, if anything, but no harm keeping
						parser.setProject(javaProject);

						//						System.out.println("Project location: " + javaProject.getOutputLocation());

						//						System.out.println(
						//								javaProject.getElementName() + "\t"
						//										+ Arrays.toString(javaProject.getResolvedClasspath(true)));

						//						String[] classpathEntries = Arrays.stream(javaProject.getResolvedClasspath(true))
						//								.map(IClasspathEntry::getPath)
						//								.map(IPath::toPortableString)
						//								//								.map(Object::toString)
						//								.toArray(String[]::new);

						//						classpathEntries = new String[] {
						//								"C:\\Users\\trshco\\Dropbox\\EclipsePlugin\\runtime-EclipseApplication\\Test\\src" };

						//						parser.setEnvironment(classpathEntries, new String[] { path.toString() },
						//								new String[] { "UTF-8" }, false);

						//						Arrays.stream(javaProject.getResolvedClasspath(true))
						//								.map(IClasspathEntry::getPath)
						//								.forEach(c -> System.out.println(c));
					}
				}
			}
		} catch (CoreException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		if (!alreadySetSource) {
			String source = new String(Files.readAllBytes(path));
			parser.setSource(source.toCharArray());
		}

		Map<String, String> options = JavaCore.getOptions();

		// Required to correctly read enums
		// http://help.eclipse.org/kepler/index.jsp?topic=%2Forg.eclipse.jdt.doc.isv%2Freference%2Fapi%2Forg%2Feclipse%2Fjdt%2Fcore%2Fdom%2FASTParser.html
		JavaCore.setComplianceOptions(JavaCore.VERSION_1_8, options);

		parser.setCompilerOptions(options);

		ASTNode astNode = parser.createAST(null);

		CompilationUnit compilationUnit;

		if (astNode instanceof CompilationUnit) {
			compilationUnit = (CompilationUnit) astNode;
		} else {
			compilationUnit = null;
		}

		ASTVisitor visitor = new ASTVisitor() {
			@Override
			public boolean visit(final MethodDeclaration methodDeclaration) {
				String elementName = methodDeclaration.getName().getIdentifier();

				IMethodBinding binding = methodDeclaration.resolveBinding();

				//				if (binding != null) {
				// TODO: need to add classpath so can resolve binding
				System.out.println(methodDeclaration.getName() + "\t" + methodDeclaration.resolveBinding());
				//				}

				Document document = new Document();

				document.add(new StringField(FULL_PATH_FIELD, pathString, Field.Store.YES));

				// Index path with normal parser, so can search path
				// (don't store, since value is already stored as part of
				// fullpath)
				document.add(new TextField(PATH_FIELD, pathString, Field.Store.NO));
				document.add(new TextField("file", filename, Field.Store.YES));
				document.add(new TextField("element", elementName, Field.Store.YES));

				if (compilationUnit != null) {
					int lineNumber = compilationUnit.getLineNumber(methodDeclaration.getName().getStartPosition());
					document.add(new IntPoint("line", lineNumber));
					document.add(new StoredField("line", lineNumber));
				}

				if (!indexWriter.isOpen()) {
					return false;
				}

				try {
					indexWriter.addDocument(document);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					//					e.printStackTrace();
				}

				return true;
			}
		};

		astNode.accept(visitor);
	}

	@NonNullByDefault
	private static String getExtension(final String filename) {
		int lastPeriod = filename.lastIndexOf('.');

		@NonNull
		@SuppressWarnings("null")
		String extension = lastPeriod > 0 ? filename.substring(lastPeriod + 1) : "";
		return extension;
	}

	private static void commit(final IndexWriter writer) throws IOException {
		writer.commit();

		// Reset the searcher, so will create a new one
		// (since want to refresh with new documents)
		if (SEARCHER_MANAGER != null) {
			SEARCHER_MANAGER.maybeRefresh();
		}
	}

	public static IndexSearcher getSearcher() throws IOException {
		if (SEARCHER_MANAGER == null) {
			SEARCHER_MANAGER = DYCESettings.getSearcherManager(DYCEIndexer.INDEX_PATH);
		}

		if (SEARCHER_MANAGER != null) {
			return SEARCHER_MANAGER.acquire();
		} else {
			return null;
		}
	}

	private static String createIndexDoneMessage(final int indexedFiles, final Duration duration) {
		String indexMessage = indexedFiles == 1 ? "1 file" : indexedFiles + " files";

		return String.format("Indexed %s. It took %s.", indexMessage, formatDuration(duration));
	}

	private static String formatDuration(final Duration duration) {
		// Based on Duration.toString();
		// (word play - notice there's no 'C' in duration)
		long time = duration.getSeconds();

		long hours = time / SECONDS_PER_HOUR;
		int minutes = (int) ((time % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE);
		int secs = (int) (time % SECONDS_PER_MINUTE);

		StringBuilder durationStringBuilder = new StringBuilder();

		if (hours != 0) {
			if (hours == 1) {
				durationStringBuilder.append("1 hour");
			} else {
				durationStringBuilder.append(hours + " hours");
			}
		}

		if (minutes != 0) {
			if (durationStringBuilder.length() > 0) {
				durationStringBuilder.append(' ');
			}

			if (minutes == 1) {
				durationStringBuilder.append("1 minute");
			} else {
				durationStringBuilder.append(minutes + " minutes");
			}
		}

		if (secs != 0) {
			if (durationStringBuilder.length() > 0) {
				durationStringBuilder.append(' ');
			}

			if (secs == 1) {
				durationStringBuilder.append("1 second");
			} else {
				durationStringBuilder.append(secs + " seconds");
			}
		}

		if (durationStringBuilder.length() == 0) {
			durationStringBuilder.append("0 seconds");
		}

		return durationStringBuilder.toString();
	}
}
