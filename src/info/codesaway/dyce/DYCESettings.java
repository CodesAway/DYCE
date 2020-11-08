package info.codesaway.dyce;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import info.codesaway.dyce.indexer.DYCEIndexer;

public class DYCESettings {
	// TODO: read from XML
	public static long DOCUMENT_VERSION = 2;

	// TODO: does it need to be concurrent?
	private static final Map<Path, SearcherManager> SEARCHER_MANAGERS = new ConcurrentHashMap<>();

	// TODO: make use of the hit limit
	public static final DYCESearcher SEARCHER_WORKSPACE = new DYCESearcher("Workspace", DYCEIndexer.INDEX_PATH,
			5);

	public static boolean shouldIndexFile(final Path path) {
		String pathString = path.toString();
		return pathString.endsWith(".java");
	}

	public static DYCESearcher getSearcherWorkspace() {
		return SEARCHER_WORKSPACE;
	}

	/**
	 *
	 * @param indexPath
	 * @return the SearcherManager or <code>null</code> if one is not yet
	 *         available (such as if the index doesn't exist yet)
	 * @throws IOException
	 */
	public static SearcherManager getSearcherManager(final Path indexPath) throws IOException {
		SearcherManager searcherManager = SEARCHER_MANAGERS.get(indexPath);

		if (searcherManager == null) {
			searcherManager = createSearcherManager(indexPath);
		}

		return searcherManager;
	}

	/**
	 * Create SearcherManager used to search the index
	 *
	 * @return the searcher or <code>null</code> if the index does not exist
	 * @throws IOException
	 */
	private static SearcherManager createSearcherManager(final Path indexPath) throws IOException {
		// TODO: see about implement NRT (Near Real Time)
		// http://blog.mikemccandless.com/2011/11/near-real-time-readers-with-lucenes.html

		// http://blog.mikemccandless.com/2011/09/lucenes-searchermanager-simplifies.html

		Directory dir = FSDirectory.open(indexPath);

		// If index doesn't already exist, cannot search it
		if (!DirectoryReader.indexExists(dir)) {
			return null;
		}

		SearcherManager searcherManager = new SearcherManager(dir, null);
		SEARCHER_MANAGERS.put(indexPath, searcherManager);
		return searcherManager;
	}

	public static void maybeRefreshSearcherManagers() {
		// As part of initialization refresh managers
		// (allows quick refreshing; also, this way refreshes if closed then
		// reoppend later)
		for (SearcherManager searcherManager : SEARCHER_MANAGERS.values()) {
			try {
				searcherManager.maybeRefresh();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

	public static void closeSearcherManagers() {
		for (SearcherManager searcherManager : SEARCHER_MANAGERS.values()) {
			try {
				searcherManager.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
}
