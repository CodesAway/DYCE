package info.codesaway.dyce;

import java.util.List;

import org.apache.lucene.search.ScoreDoc;
import org.eclipse.jdt.annotation.Nullable;

public class DYCESearchResult {
	private final DYCESearch search;
	private final List<DYCESearchResultEntry> results;
	private final String message;
	private final boolean isIndexCreated;

	/**
	 * Last document returned in search (used to allow search after)
	 */
	@Nullable
	private final ScoreDoc lastDocument;

	public DYCESearchResult(final DYCESearch search, final List<DYCESearchResultEntry> results,
			final String message, final boolean isIndexCreated, @Nullable final ScoreDoc lastDocument) {
		this.search = search;
		this.results = results;
		this.message = message;
		this.isIndexCreated = isIndexCreated;
		this.lastDocument = lastDocument;
	}

	public DYCESearch getSearch() {
		return this.search;
	}

	public List<DYCESearchResultEntry> getResults() {
		return this.results;
	}

	public String getMessage() {
		return this.message;
	}

	public boolean isIndexCreated() {
		return this.isIndexCreated;
	}

	@Nullable
	public ScoreDoc getLastDocument() {
		return this.lastDocument;
	}
}
