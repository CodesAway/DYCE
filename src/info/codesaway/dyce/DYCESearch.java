package info.codesaway.dyce;

import java.util.Objects;

import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.queryparser.flexible.standard.config.StandardQueryConfigHandler.Operator;
import org.eclipse.jdt.annotation.Nullable;

public class DYCESearch {
	private final String text;
	//	private final long delay;
	//	private final boolean shouldSelectFirstResult;
	private final int hitLimit;
	//	private final Optional<Query> extraQuery;
	private final DYCESearcher searcher;
	private final Operator defaultOperator;
	//	private final boolean shouldIncludeComments;
	// Comment

	//	public DYCESearch(final String text, final long delay, final boolean shouldSelectFirstResult, final int hitLimit,
	//			final Optional<Query> extraQuery, final DYCESearcher searcher, final Operator defaultOperator,
	//			final boolean shouldIncludeComments) {
	public DYCESearch(final String text, final int hitLimit, final DYCESearcher searcher,
			final Operator defaultOperator) {
		this.text = text;
		//		this.delay = delay;
		//		this.shouldSelectFirstResult = shouldSelectFirstResult;
		this.hitLimit = hitLimit;
		//		this.extraQuery = extraQuery;
		this.searcher = searcher;
		this.defaultOperator = defaultOperator;
		//		this.shouldIncludeComments = shouldIncludeComments;
	}

	public String getText() {
		return this.text;
	}

	//	public long getDelay() {
	//		return this.delay;
	//	}

	//	public boolean hasDelay() {
	//		return this.getDelay() != 0;
	//	}

	//	public boolean shouldSelectFirstResult() {
	//		return this.shouldSelectFirstResult;
	//	}

	public int getHitLimit() {
		return this.hitLimit;
	}

	//	public Optional<Query> getExtraQuery() {
	//		return this.extraQuery;
	//	}

	public DYCESearcher getSearcher() {
		return this.searcher;
	}

	public Operator getDefaultOperator() {
		return this.defaultOperator;
	}

	public org.apache.lucene.queryparser.classic.QueryParser.Operator getClassicDefaultOperator() {
		switch (this.getDefaultOperator()) {
		case AND:
			return QueryParser.Operator.AND;
		case OR:
			return QueryParser.Operator.OR;
		default:
			return QueryParser.Operator.OR;
		}
	}

	//	public boolean shouldIncludeComments() {
	//		return this.shouldIncludeComments;
	//	}

	@Override
	public int hashCode() {
		return Objects.hash(this.hitLimit, this.text, this.searcher, this.defaultOperator);
		//		return Objects.hash(this.extraQuery, this.hitLimit, this.text, this.searcher, this.defaultOperator,
		//				this.shouldIncludeComments);
	}

	@Override
	public boolean equals(final @Nullable Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (this.getClass() != obj.getClass()) {
			return false;
		}
		DYCESearch other = (DYCESearch) obj;
		return this.hitLimit == other.hitLimit
				&& Objects.equals(this.text, other.text) && Objects.equals(this.searcher, other.searcher)
				&& this.defaultOperator == other.defaultOperator;
		//				&& this.shouldIncludeComments == other.shouldIncludeComments;
		//		return Objects.equals(this.extraQuery, other.extraQuery) && this.hitLimit == other.hitLimit
		//				&& Objects.equals(this.text, other.text) && Objects.equals(this.searcher, other.searcher)
		//				&& this.defaultOperator == other.defaultOperator
		//				&& this.shouldIncludeComments == other.shouldIncludeComments;
	}

	//	@Override
	//	public String toString() {
	//		@NonNull
	//		@SuppressWarnings("null")
	//		String toString = String.format(
	//				"DYCE Searching top %d hits for %s%s (%s; defaultOperator = %s; includeComments = %s)", this.hitLimit,
	//				this.text, this.extraQuery.isPresent() ? " with extra query " + this.extraQuery.get() : "",
	//				this.searcher.getIndexPath(), this.defaultOperator, this.shouldIncludeComments);
	//
	//		return toString;
	//	}
}
