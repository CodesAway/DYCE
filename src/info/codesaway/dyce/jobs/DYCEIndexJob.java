package info.codesaway.dyce.jobs;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.apache.lucene.index.Term;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.swt.widgets.Display;

import info.codesaway.dyce.DYCEView;
import info.codesaway.dyce.indexer.DYCEIndexer;
import info.codesaway.util.indexer.PathWithLastModified;
import info.codesaway.util.indexer.PathWithTerm;

public class DYCEIndexJob extends Job {
	private final DYCEView view;

	private final Set<PathWithLastModified> indexPaths = ConcurrentHashMap.newKeySet();
	private final Set<Term> deleteDocuments = ConcurrentHashMap.newKeySet();
	private boolean incrementalRebuildIndex = false;

	/**
	 * Delay after search is done before should show index message
	 *
	 * <p>
	 * Helps to prevent search done then index shortly done and wiping out the
	 * search result message
	 * </p>
	 *
	 * <p>
	 * Defaults to 10 seconds
	 * </p>
	 */
	private static final long MESSAGE_DELAY = 10 * 1000;

	public static final Term[] EMPTY_TERM_ARRAY = {};

	public DYCEIndexJob(final DYCEView view) {
		super("DYCE Indexing");

		this.view = view;
	}

	// TODO: add these back?
	// private Label getStatusLabel() {
	// return this.view.getStatusLabel();
	// }
	//
	// private Label getMessageLabel() {
	// return this.view.getMessageLabel();
	// }
	//
	// private TableViewer getViewer() {
	// return this.view.getViewer();
	// }

	public void setIndexCreated(final boolean isIndexCreated) {
		this.view.setIndexCreated(isIndexCreated);
	}

	public void schedule(final boolean incrementalRebuildIndex, final Collection<PathWithLastModified> indexPaths,
			final Collection<Term> deleteDocuments) {
		// TODO: don't use parameter for anything yet
		// (not sure if I need to, other than to give user heads up possibly)
		// (though you can query while indexing, unless it's the first time the
		// index
		// has been built)
		this.incrementalRebuildIndex |= incrementalRebuildIndex;

		if (!indexPaths.isEmpty()) {
			// System.out.println("Need to index: " + indexPaths);
			this.indexPaths.addAll(indexPaths);
		}

		if (!deleteDocuments.isEmpty()) {
			this.deleteDocuments.addAll(deleteDocuments);
		}

		this.schedule();
	}

	public void removePath(final PathWithTerm path) {
		// System.out.println("Indexed " + path);
		this.indexPaths.remove(path);
	}

	@Override
	protected IStatus run(final IProgressMonitor monitor) {
		if (monitor.isCanceled()) {
			return Status.CANCEL_STATUS;
		}

		@Nullable
		Display display = this.view.getDisplay();

		// Set units of work
		// https://www.eclipse.org/articles/Article-Progress-Monitors/article.html
		// TODO: doesn't look like I can use since not thread-safe (due to
		// performance)
		// So rebuilding the index will just suck
		// TODO: break full index into multiple steps
		// This way, can index and commit in chunks (such as starting with
		// recently
		// modified files)
		// This way, each chunk wouldn't take long to process and the user could
		// search
		// these recent files, while the older files are indexed
		// SubMonitor subMonitor = SubMonitor.convert(monitor);

		String message = "";

		//		if (display != null) {
		//			display.syncExec(() -> {
		//				// Run in UI
		//				this.view.setStatus(DYCEView.INDEXING_STATUS);
		//			});
		//		}

		try {
			// First index specific paths, if any
			// (this way, recently modified files are indexed even if the entire
			// index isn't
			// built yet or needs to be updated)
			if (!this.indexPaths.isEmpty() || !this.deleteDocuments.isEmpty()) {
				Stream<PathWithLastModified> stream = this.indexPaths.parallelStream();
				Term[] deletes = this.deleteDocuments.isEmpty() ? EMPTY_TERM_ARRAY
						: this.deleteDocuments.toArray(EMPTY_TERM_ARRAY);

				// Index the files and remove them from indexPaths as they are
				// being indexed
				// Note: any deleted files will be part of indexPaths, so don't
				// pass anything
				// specific for deleted
				DYCEIndexer.index(stream, this, monitor, true, deletes);

				if (monitor.isCanceled()) {
					return Status.CANCEL_STATUS;
				}
			}

			if (this.incrementalRebuildIndex) {
				message = DYCEIndexer.incrementalRebuildIndex(this, monitor);
				this.incrementalRebuildIndex = false;

				if (monitor.isCanceled()) {
					return Status.CANCEL_STATUS;
				}
			}

			return Status.OK_STATUS;
		} catch (IOException e) {
			if (display != null) {
				display.syncExec(() -> {
					// Run in UI
					this.view.setStatus(DYCEView.ERROR_STATUS);
					this.view.setMessage("Could not index. Please try again later.");
				});
			}

			e.printStackTrace();

			return Status.OK_STATUS;
		}
		//		finally {
		//			// Used to make lambda happy
		//			String finalMessage = message;
		//			if (display != null) {
		//				display.syncExec(() -> {
		//					// Run in UI
		//
		//					//					if (this.view.getStatus().equals(DYCEView.INDEXING_STATUS)) {
		//					//						this.view.setStatus("");
		//					//					}
		//
		//					if (!finalMessage.isEmpty() && this.view.getStatus().isEmpty()
		//							&& this.view.getLastSearchDoneTime() < System.currentTimeMillis() - MESSAGE_DELAY) {
		//
		//						this.view.setMessage(finalMessage);
		//					}
		//				});
		//			}
		//		}
	}
}
