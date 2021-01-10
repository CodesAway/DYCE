package info.codesaway.dyce;

import static info.codesaway.dyce.grammar.DYCEGrammarUtilities.convertLineTextToSentence;
import static info.codesaway.dyce.grammar.DYCEGrammarUtilities.convertSentenceToCode;
import static info.codesaway.dyce.grammar.DYCEGrammarUtilities.determineClassName;
import static info.codesaway.dyce.grammar.DYCEGrammarUtilities.determineCodeForSentence;
import static info.codesaway.dyce.util.EclipseUtilities.getActiveDocument;
import static info.codesaway.dyce.util.EclipseUtilities.getActivePathname;
import static info.codesaway.dyce.util.EclipseUtilities.getDocumentLine;
import static info.codesaway.dyce.util.EclipseUtilities.insertText;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import javax.annotation.PostConstruct;

import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.flexible.standard.config.StandardQueryConfigHandler.Operator;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.ui.model.WorkbenchLabelProvider;
import org.eclipse.ui.part.ViewPart;

import info.codesaway.bex.Indexed;
import info.codesaway.dyce.grammar.DYCEGrammarUtilities;
import info.codesaway.dyce.indexer.DYCEIndexer;
import info.codesaway.dyce.jobs.DYCEIndexJob;
import info.codesaway.dyce.jobs.DYCESearchJob;
import info.codesaway.util.indexer.PathWithLastModified;
import info.codesaway.util.indexer.PathWithTerm;
import info.codesaway.util.regex.Matcher;
import info.codesaway.util.regex.Pattern;

// Source: https://www.vogella.com/tutorials/EclipseJFaceTable/article.html
public class DYCEView extends ViewPart {
	public static final String ID = DYCEView.class.getName();

	public static DYCEView INSTANCE;

	private StyledText searchText;

	public static final String ERROR_STATUS = "ERROR";

	private static DYCEIndexJob indexJob;
	private static DYCESearchJob searchJob;

	private TableViewer viewer;

	private long lastIndexTime = 0;

	// Initialize with true, so if index doesn't exist,
	// the first time we query, it will create the index
	// (since isIndexCreated is true and if the index didn't exist when call
	// setIndexCreated would be false)
	// (as a result, setIndexCreated will run the indexer)
	// (whereas if isIndexCreated were initialized to false, calling
	// setIndexCreated with false would do nothing, since the value didn't
	// change)
	private boolean isIndexCreated = true;

	// If user hasn't typed anything for a while,
	// perform an incremental indexing when they start typing again
	// (this way, any changes can be indexed, in case modified files outside of Eclipse)
	private static int INDEX_DELAY = 5 * 60 * 1000;

	private static final ThreadLocal<Matcher> SEARCH_TEXT_FORMATTER_MATCHERS = Pattern
			.getThreadLocalMatcher("\\w++(?=:)");

	private static IResourceChangeListener RESOURCE_CHANGE_LISTENER = event -> {
		// https://www.eclipse.org/articles/Article-Resource-deltas/resource-deltas.html
		// if (event.getType() != IResourceChangeEvent.POST_CHANGE) {
		// return;
		// }

		ArrayDeque<IResourceDelta> deltas = new ArrayDeque<>();
		ArrayDeque<PathWithLastModified> paths = new ArrayDeque<>();
		ArrayDeque<Term> deletes = new ArrayDeque<>();
		deltas.add(event.getDelta());

		while (!deltas.isEmpty()) {
			IResourceDelta delta = deltas.remove();

			// System.out.println(
			// "Checking Delta: " + delta.getKind() + ": " +
			// delta.getFlags() + ": " +
			// delta.getFullPath());

			IResourceDelta[] children = delta.getAffectedChildren();

			// Is a file
			if (children.length == 0) {
				// The content was modified or it's a new file
				if ((delta.getFlags() & IResourceDelta.CONTENT) != 0
						|| (delta.getKind() & IResourceDelta.ADDED) != 0) {
					IResource resource1 = delta.getResource();
					// IJavaElement element = JavaCore.create(resource);
					// if (element instanceof ICompilationUnit) {
					// ICompilationUnit unit = (ICompilationUnit) element;
					// unit.getTypes();
					// }
					// JavaCore.createCompilationUnitFrom(null)

					Path path = resource1.getRawLocation().toFile().toPath();

					if (DYCESettings.shouldIndexFile(path)) {
						IProject iProject = resource1.getProject();
						String project = iProject != null ? iProject.getName() : "";
						// System.out.println("Changed! " + path);
						paths.add(new PathWithLastModified(project, path));
						//						paths.add(PathWithTerm.wrap(project, path));
					}
				} else if ((delta.getKind() & IResourceDelta.REMOVED) != 0) {
					// || (delta.getKind() & IResourceDelta.REMOVED_PHANTOM)
					// != 0) {
					// Handle removed files
					// (in this case, just need to delete the documents from
					// the index

					IResource resource2 = delta.getResource();
					@NonNull
					@SuppressWarnings("null")
					String pathname = resource2.getRawLocation().toFile().toPath().toString();

					deletes.add(PathWithTerm.getTerm(pathname));
				}
			} else {
				for (IResourceDelta child : children) {
					deltas.add(child);
				}
			}
		}

		// For the added paths, add them to the collection of files to index
		if (!paths.isEmpty() || !deletes.isEmpty()) {
			indexJob.cancel();
			indexJob.schedule(false, paths, deletes);
		}
	};

	@Override
	@PostConstruct
	public void createPartControl(final Composite parent) {
		// TODO: see how can use information

		// org.eclipse.jdt.ui.java_string

		GridLayout layout = new GridLayout(2, false);
		// GridLayout layout = new GridLayout(3, false);
		parent.setLayout(layout);

		this.createSearchText(parent);

		this.createViewer(parent);

		indexJob = new DYCEIndexJob(this);
		searchJob = new DYCESearchJob(this);

		// Add change listener here instead of Activator
		// (since don't need to watch for changes unless the CASTLE Searching
		// view exists)
		// (since until the view exists, I don't create and run the indexer)
		//		Activator.WORKSPACE.addResourceChangeListener(RESOURCE_CHANGE_LISTENER, IResourceChangeEvent.POST_CHANGE);

		// TODO: use information about the workspace when indexing
		// https://www.vogella.com/tutorials/EclipseJDT/article.html
		// DisplayProjectInformation.display(Activator.WORKSPACE_ROOT);

		Activator.WORKSPACE.addResourceChangeListener(RESOURCE_CHANGE_LISTENER, IResourceChangeEvent.POST_CHANGE);

		INSTANCE = this;

		// Index the current files when the view is created
		this.index();
	}

	private void createViewer(final Composite parent) {
		// Source:
		// https://www.vogella.com/tutorials/EclipseJFaceTable/article.html
		this.viewer = new TableViewer(parent,
				SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.BORDER);

		this.createColumns(parent, this.viewer);

		final Table table = this.viewer.getTable();
		table.setHeaderVisible(true);
		table.setLinesVisible(true);

		this.viewer.setContentProvider(new ArrayContentProvider());
		this.viewer.getControl().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));
		// this.viewer.getControl().setLayoutData(new GridData(SWT.FILL,
		// SWT.FILL, true, true, 3, 1));
		// TODO: how to use the text font in the view too?
		// (so changes to it will change the view as well)
		// https://www.vogella.com/tutorials/EclipseEditors/article.html#adding-colors-and-fonts-preferences
		// this.viewer.getControl().setFont(JFaceResources.getTextFont());

		// TODO: make the selection available to other views
		// this.getSite().setSelectionProvider(this.viewer);

		// Layout the viewer
		// GridData gridData = new GridData();
		// gridData.verticalAlignment = GridData.FILL;
		// gridData.horizontalSpan = 3;
		// gridData.grabExcessHorizontalSpace = true;
		// gridData.grabExcessVerticalSpace = true;
		// gridData.horizontalAlignment = GridData.FILL;
		// this.viewer.getControl().setLayoutData(gridData);

		// Double click to open file
		// https://stackoverflow.com/a/6342124
		// TODO: implement
		//		this.viewer.addDoubleClickListener(event -> {
		//			IStructuredSelection selection = (IStructuredSelection) this.viewer.getSelection();
		//			if (selection.isEmpty()) {
		//				return;
		//			}
		//
		//			@SuppressWarnings("unchecked")
		//			List<DYCESearchResultEntry> list = selection.toList();
		//
		//			LinkedHashMap<String, DYCESearchResultEntry> openResults = list.stream()
		//					.collect(Collectors.toMap(
		//							// Group by path, since can only open each file once
		//							// (in this case, want to get the line number
		//							// corresponding to the more
		//							// important, earlier entry)
		//							DYCESearchResultEntry::getPath,
		//
		//							// For the value, use the CASTLESearchResultEntry
		//							// object itself
		//							Function.identity(),
		//
		//							// Merge function, always use the old value if
		//							// multiple for same path
		//							// (this way, get the one with higher importance)
		//							(old, e) -> old,
		//
		//							// Create as LinkedHashMap, so keep insertion order
		//							// (since will then iterate in reverse order when
		//							// opening the results)
		//							LinkedHashMap::new));
		//
		//			List<DYCESearchResultEntry> entries = new ArrayList<>(openResults.values());
		//
		//			// TODO: make preference
		//			int maxOpenFiles = 10;
		//
		//			if (entries.size() > maxOpenFiles) {
		//				entries = entries.subList(0, maxOpenFiles);
		//			}
		//
		//			// Iterate in reverse order, so most import page has focus
		//			// (last page opened is the most important)
		//			for (int i = entries.size() - 1; i >= 0; i--) {
		//				DYCESearchResultEntry entry = entries.get(i);
		//
		//				this.openResult(entry);
		//			}
		//		});

		// TODO: implement
		//		this.viewer.getControl().addKeyListener(new KeyAdapter() {
		//			@Override
		//			public void keyPressed(final KeyEvent e) {
		//				DYCEView.this.handleKeyPressedInViewer(e);
		//			}
		//
		//			@Override
		//			public void keyReleased(final KeyEvent e) {
		//				DYCEView.this.handleKeyReleasedInViewer(e);
		//			}
		//		});
	}

	// This will create the columns for the table
	private void createColumns(final Composite parent, final TableViewer viewer) {
		// https://www.programcreek.com/java-api-examples/?code=gw4e/gw4e.project/gw4e.project-master/bundles/gw4e-eclipse-plugin/src/org/gw4e/eclipse/wizard/convert/page/TableHelper.java
		// Make last column width based on column width left

		// TODO: calculate width based on available space

		this.createTableViewerColumn("#", 50, DYCESearchResultEntry::getResultNumber);
		this.createTableViewerColumn("Package", 250, DYCESearchResultEntry::getPackageName);
		this.createTableViewerColumn("Class", 150, DYCESearchResultEntry::getClassName);
		this.createTableViewerColumn("Element", 250, DYCESearchResultEntry::getElement);
		this.createTableViewerColumn("Line", 65, DYCESearchResultEntry::getLine);
		// Put type before content, since can use type for quick understanding
		// of line
		this.createTableViewerColumn("Type", 250, DYCESearchResultEntry::getType);
		//		this.createStyledTableViewerColumn("Content", 750, DYCEView::getStyledContent);
		this.createTableViewerColumn("File", 250, DYCESearchResultEntry::getFile);
		this.createTableViewerColumn("Path", 1000, DYCESearchResultEntry::getPath);
	}

	private TableViewerColumn createTableViewerColumn(final String title, final int width) {
		TableViewerColumn viewerColumn = new TableViewerColumn(this.viewer, SWT.NONE);
		TableColumn column = viewerColumn.getColumn();
		column.setText(title);
		column.setWidth(width);
		column.setResizable(true);
		column.setMoveable(true);

		return viewerColumn;
	}

	private TableViewerColumn createTableViewerColumn(final String title, final int width,
			final Function<DYCESearchResultEntry, String> valueFunction) {
		TableViewerColumn viewerColumn = this.createTableViewerColumn(title, width);

		viewerColumn.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(final Object element) {
				return valueFunction.apply((DYCESearchResultEntry) element);
			}
		});

		return viewerColumn;
	}

	private TableViewerColumn createStyledTableViewerColumn(final String title, final int width,
			final Function<DYCESearchResultEntry, StyledString> valueFunction) {
		TableViewerColumn viewerColumn = this.createTableViewerColumn(title, width);

		IStyledLabelProvider labelProvider = new WorkbenchLabelProvider();

		viewerColumn.setLabelProvider(new DelegatingStyledCellLabelProvider(labelProvider) {
			@Override
			protected StyledString getStyledText(final Object element) {
				return valueFunction.apply((DYCESearchResultEntry) element);
			}
		});

		return viewerColumn;
	}

	protected void handleKeyPressedInSearchText(final KeyEvent e) {
		// Start indexing if start typing and haven't indexed for a while
		if (System.currentTimeMillis() - this.lastIndexTime >= INDEX_DELAY) {
			// Perform an incremental index update
			this.index();
			DYCESettings.maybeRefreshSearcherManagers();
		}

		switch (e.keyCode) {
		// enter pressed
		case SWT.CR:
		case SWT.KEYPAD_CR:
		case SWT.F5:

			// Good test case since actual webContext variable name as well as webCtx, depending on scope
			// Only should be checking variables in scope
			//			determineVariableName("web context");
			// TODO: testing in static block of BaseAction
			// Why can't it find the correct variable with just "context"?
			// Why can't it find the DEBUG field?
			determineCodeForSentence(this.getText());

			if (true) {
				return;
			}

			//			determineVariableName("name context");
			//			determineVariableName("debug");
			//			determineVariableName("request");
			//			determineVariableName("event");
			//			determineVariableName("parameters");
			//			determineVariableName("string");
			//			determineVariableName("int");
			//			determineVariableName("message");
			//			determineVariableName("console message");
			//			determineVariableName("request message");
			//			determineVariableName("request messages");
			//			determineVariableName("error");

			//			determineClassName("error message");
			//			determineClassName("err mess");
			//			determineClassName("information message");
			//			determineClassName("service manager");
			//			determineClassName("action for");
			//			determineClassName("action ward");
			// Usually matches ActionForm, but really close also to ActionForward
			//			determineClassName("action word");
			//			determineClassName("tee map");
			//			determineClassName("action for word");
			//			determineClassName("action for ward");
			//			determineClassName("naming");
			//			determineClassName("Servlet");
			//			determineClassName("message list");
			//			determineClassName("list message");
			// Action is part of impors, but there's also a BaseAction class which is a perfect match
			//			determineClassName("base action");
			//			determineClassName("maintain");
			determineClassName("comment out");
			//			determineClassName("comment");

			// 7 * 0.75 + 6 + 21
			String text = this.getText();

			// TODO: try to search using query
			// If find results, then perform search and don't try to insert text
			if (!text.isEmpty()) {
				String codeResult = DYCEGrammarUtilities.convertSentenceToCode(text);
				insertText(codeResult);
			}

			Indexed<Optional<String>> activePathname = getActivePathname();

			if (activePathname.getValue().isPresent()) {
				String pathname = activePathname.getValue().get();
				int currentLine = activePathname.getIndex();

				System.out.printf("In %s on line %d%n", pathname, currentLine);
				Optional<IDocument> optionalDocument = getActiveDocument();

				if (optionalDocument.isPresent()) {
					// Subtract 1 since document's lines start with 0 whereas UI start with 1
					int documentLine = currentLine - 1;
					IDocument document = optionalDocument.get();
					try {
						//						String currentLineText = DYCEUtilities.getDocumentLine(document, documentLine);
						//						System.out.println("Current line: " + currentLineText);

						String sentence = convertLineTextToSentence(document, documentLine);
						String code = convertSentenceToCode(sentence);

						if (code.trim().equals(getDocumentLine(document, documentLine).trim())) {
							System.out.println("Great job!");
						}
					} catch (BadLocationException e1) {
					}
				}
			}

			this.searchText.selectAll();
			//			this.outputMethods();

			int hitLimit = 5;
			//			int hitLimit = this.getHitLimit(e);
			//
			//			if (this.isIncrementalSearch() && DEFAULT_INCREMENTAL_HIT_LIMIT > hitLimit) {
			//				hitLimit = DEFAULT_INCREMENTAL_HIT_LIMIT;
			//			}

			//			this.search(0, false, hitLimit);
			this.search(hitLimit);
			return;
		}
	}

	//	/**
	//	 *
	//	 *
	//	 * @param delay
	//	 *            the delay to which before starting the search (enter 0 to
	//	 *            search immediately)
	//	 */
	//	public void search(final long delay, final boolean shouldSelectFirstResult, final int hitLimit) {
	//		this.search(delay, shouldSelectFirstResult, hitLimit, Optional.empty());
	//		//		this.search(delay, shouldSelectFirstResult, hitLimit, this.getExtraQuery(this.isIncrementalSearch()));
	//	}

	@NonNullByDefault
	public void search(final int hitLimit) {
		//	public void search(final long delay, final boolean shouldSelectFirstResult, final int hitLimit,
		//			final Optional<Query> extraQuery) {
		//		DYCESearcher searcher = DYCESettings.getSearcher(this.comboDropDown.getText());
		DYCESearcher searcher = DYCESettings.getSearcherWorkspace();

		if (searcher == null) {
			return;
		}

		Path indexPath = searcher.getIndexPath();

		if (indexPath == null) {
			return;
		}

		String text = this.getText();

		//		System.out.println("Search for " + text);

		Operator defaultOperator = Operator.AND;
		//		boolean shouldIncludeComments = false;
		//		Operator defaultOperator = this.getDefaultOperator();
		//		boolean shouldIncludeComments = this.shouldIncludeComments();

		DYCESearch search = new DYCESearch(text, hitLimit, searcher, defaultOperator);

		//		DYCESearch search = new DYCESearch(text, delay, shouldSelectFirstResult, hitLimit, extraQuery, searcher,
		//				defaultOperator, shouldIncludeComments);

		// Determines whether to run the current search or the new search
		searchJob.handleSearch(search);
	}

	//	private void outputMethods() {
	//		// Get files in project
	//		// https://stackoverflow.com/a/4959190
	//		// (uses project.members(), but not recursive)
	//
	//		IResourceVisitor visitor = resource -> {
	//			System.out.printf("%s\t%s%n", resource.getFullPath(), resource.getClass());
	//
	//			if (resource instanceof IFile && Objects.equals(resource.getFileExtension(), "java")) {
	//				IFile file = (IFile) resource;
	//
	//				// TODO: this only works on Java files
	//				// (what if only have .class files, like in JAR?)
	//				ICompilationUnit compilationUnit = JavaCore.createCompilationUnitFrom(file);
	//				//			System.out.println("Parse: " + file);
	//
	//				try {
	//					// Note: offset 0 is line 0
	//					// (so add 1 to line number to match what would show on the screen)
	//					DefaultLineTracker lineTracker = new DefaultLineTracker();
	//					lineTracker.set(compilationUnit.getSource());
	//
	//					IType[] types = compilationUnit.getTypes();
	//					for (IType type : types) {
	//						IField[] fields = type.getFields();
	//						for (IField field : fields) {
	//							String elementName = field.getElementName();
	//							ISourceRange sourceRange = field.getSourceRange();
	//
	//							System.out.printf("Field: %s %s %s\t%s%n", Flags.toString(field.getFlags()),
	//									Signature.toString(field.getTypeSignature()),
	//									elementName, field.getConstant() != null ? field.getConstant() : "");
	//							//						JDTUtilities.addRange(sourceRange, elementName, javaElements, lineTracker);
	//						}
	//
	//						IMethod[] methods = type.getMethods();
	//						for (IMethod method : methods) {
	//							System.out.printf("%s %s%n", Flags.toString(method.getFlags()),
	//									Signature.toString(method.getSignature(), method.getElementName(),
	//											method.getParameterNames(), false, true));
	//
	//							ISourceRange sourceRange = method.getSourceRange();
	//							//						JDTUtilities.addRange(sourceRange, elementName, javaElements, lineTracker);
	//						}
	//					}
	//				} catch (JavaModelException e) {
	//					// Do nothing
	//					// (if there's an error, just don't index line info
	//				}
	//			}
	//
	//			return true;
	//		};
	//
	//		for (IProject project : Activator.WORKSPACE_ROOT.getProjects()) {
	//			// Note: can only access open projects
	//			try {
	//				project.accept(visitor);
	//			} catch (CoreException e) {
	//				// TODO Auto-generated catch block
	//				e.printStackTrace();
	//			}
	//		}
	//	}

	private void createSearchText(final Composite parent) {
		this.searchText = new StyledText(parent, SWT.BORDER | SWT.SEARCH | SWT.SINGLE);
		this.searchText.setLayoutData(new GridData(SWT.FILL, SWT.NONE, true, false));
		// this.searchText.setLayoutData(new
		// GridData(GridData.FILL_HORIZONTAL));
		// this.searchText.setLayoutData(new GridData(GridData.GRAB_HORIZONTAL |
		// GridData.HORIZONTAL_ALIGN_FILL));
		this.searchText.addModifyListener(event -> {
			// Format text
			this.styleSearchText();
		});

		this.searchText.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(final KeyEvent e) {
				DYCEView.this.handleKeyPressedInSearchText(e);
			}

			//			@Override
			//			public void keyReleased(final KeyEvent e) {
			//				DYCEView.this.handleKeyReleasedInSearchText(e);
			//			}
		});
	}

	public TableViewer getViewer() {
		return this.viewer;
	}

	//	@NonNullByDefault
	public String getText() {
		// Trim since leading / trailing whitespace doesn't matter
		//		@NonNull
		@SuppressWarnings("null")
		String text = this.searchText.getText().trim();
		return text;
	}

	@Override
	// Set to ensure focus is set correctly
	// (tried without and didn't focus correctly for some reason)
	// @Focus
	public void setFocus() {
		this.searchText.setFocus();
		// TODO: make setting whether to select all when set focus
		// TODO: when would this be annoying?
		this.searchText.selectAll();
	}

	public void selectAndRevealTopResult() {
		// Set focus, so if switch setting (like incremental or searcher) will
		// then will still select the first element
		this.getViewer().getControl().setFocus();

		// Top result (or null or there are no results)
		Object element = this.getViewer().getElementAt(0);

		this.getViewer().setSelection(new StructuredSelection(element), true);
	}

	public void revealTopResult() {
		// Top result (or null or there are no results)
		Object element = this.getViewer().getElementAt(0);

		this.getViewer().reveal(element);
	}

	private void styleSearchText() {
		Matcher matcher = SEARCH_TEXT_FORMATTER_MATCHERS.get().reset(this.searchText.getText());

		List<StyleRange> ranges = new ArrayList<>();

		while (matcher.find()) {
			int length = matcher.end() - matcher.start();
			ranges.add(new StyleRange(matcher.start(), length, null, null, SWT.BOLD));
		}

		this.searchText.setStyleRanges(ranges.toArray(new StyleRange[0]));
	}

	public void index() {
		this.lastIndexTime = System.currentTimeMillis();
		// Cancel existing index job
		// (this way, will pick up recently modified files if want to refresh
		// index)
		// TODO: also add progress indicator, since now going in order
		indexJob.cancel();
		indexJob.schedule(true, Collections.emptyList(), Collections.emptyList());
	}

	public static boolean isIndexing() {
		return indexJob.getState() == Job.RUNNING;
	}

	public static void cancelIndexing() {
		indexJob.cancel();
	}

	public void setIndexCreated(final boolean isIndexCreated) {
		if (isIndexCreated == this.isIndexCreated) {
			// Don't need to do anything
			return;
		}

		// Index state changed
		// 1) Currently, index doesn't exist
		// a) Need to index
		// b) When index exists, refresh search TODO:
		// 2) Index now exists and previously didn't
		// TODO: do I need to do anything in this case?

		this.isIndexCreated = isIndexCreated;

		if (!isIndexCreated && indexJob.getState() != Job.RUNNING) {
			this.index();
		} else if (isIndexCreated) {
			// TODO: implement

			//			// Index now exists and previously didn't
			//			// Perform search on current query
			//			Display display = this.getViewer().getControl().getDisplay();
			//
			//			display.syncExec(() -> {
			//				// In UI thread
			//				this.search();
			//			});
		}
	}

	/*
	private static StyledString getStyledContent(final DYCESearchResultEntry entry) {
		// TODO: how to prevent meta documents from being returned in results
		// TODO: replace tabs with spaces in content (since tabs don't seem to
		// show, at all, causing text to get squished together)

		if (entry.getExtension() == null) {
			// Added to prevent NullPointerException if there was no content
			// (such as if metadocument was incorrectly returned)
			if (entry.getContent() == null) {
				return new StyledString();
			}

			return new StyledString(entry.getContent());
		}

		// TODO: implement
		//		if (entry.getExtension().equals("java")) {
		//			return CASTLEJavaIndexer.getJavaStyledContent(entry);
		//		}

		return new StyledString(entry.getContent());

		// result.append(entry.getContent(), StyledString.COUNTER_STYLER);
		// result.append(entry.getContent(), StyledString.DECORATIONS_STYLER);
		// result.append(entry.getContent(), StyledString.QUALIFIER_STYLER);
		// // result.append(entry.getContent(),
		// StyledString.createColorRegistryStyler(JFacePreferences.ERROR_COLOR,
		// null));
		// result.append(entry.getContent(),
		// StyledString.createColorRegistryStyler(
		// JFacePreferences.INFORMATION_FOREGROUND_COLOR,
		// JFacePreferences.INFORMATION_BACKGROUND_COLOR));
		//
		// //
		// https://www.javatips.net/api/org.eclipse.koneki.ldt-master/plugins/org.eclipse.koneki.ldt.ui/src/org/eclipse/koneki/ldt/ui/internal/buildpath/LuaExecutionEnvironmentLabelProvider.java
		// result.append(entry.getContent(), BOLD_STYLER);
		//
		// result.append(entry.getContent(), JAVA_STRING_STYLER);
	}
	*/

	public String getStatus() {
		//		if (this.statusLabel.isDisposed()) {
		return "UNKNOWN";
		//		}
		//
		//		return this.statusLabel.getText();
	}

	public void setStatus(final String status) {
		//		if (!this.statusLabel.isDisposed()) {
		//			this.statusLabel.setText(status);
		//		}
	}

	public String getMessage() {
		//		if (this.messageLabel.isDisposed()) {
		return "UNKNOWN";
		//		}
		//
		//		return this.messageLabel.getText();
	}

	public void setMessage(final String message) {
		//		if (!this.messageLabel.isDisposed()) {
		//			this.messageLabel.setText(message);
		//		}
	}

	public void setResults(final List<DYCESearchResultEntry> results) {
		this.viewer.setInput(results);
	}

	// TODO: implement
	public static void refreshFonts(final Font font) {

	}

	//	@Nullable
	public Display getDisplay() {
		if (this.searchText.isDisposed()) {
			return null;
		}

		return this.searchText.getDisplay();
	}

	public static void cancelJobs() {
		Activator.WORKSPACE.removeResourceChangeListener(RESOURCE_CHANGE_LISTENER);

		//		DYCESettings.stopWatchingSettings();

		if (indexJob != null) {
			indexJob.cancel();

			try {
				indexJob.join();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		if (searchJob != null) {
			searchJob.cancel();
			try {
				searchJob.join();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		DYCESettings.closeSearcherManagers();

		DYCEIndexer.closeWriter();
	}
}
