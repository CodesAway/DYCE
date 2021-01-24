package info.codesaway.dyce.grammar;

import java.util.Collection;

/**
 *
 * @param <I> the extra information used when determine matches (such as cached info)
 */
public interface CodeMatchResult<I> extends CodeMatch {
	int getWordCount();

	/**
	 * Creates a new result which adds the specified unmatched word
	 * @param unmatchedWord the unmatched word to add
	 *
	 * @return a new CodeMatchResult containing this CodeMatchResult's matches and the specified <code>match</code> added to the end
	 */
	public CodeMatchResult<I> addUnmatchedWord(final String unmatchedWord);

	public String getUnmatchedWords();

	public default boolean hasUnmatchedWords() {
		return !this.getUnmatchedWords().isEmpty();
	}

	/**
	 * Gets the unmatched words, appending the specified <code>nextUnmatchedWord</code>
	 * @param nextUnmatchedWord the next unmatched word
	 * @return the unmatched words, appending the specified <code>nextUnmatchedWord</code>
	 */
	public default String getUnmatchedWords(final String nextUnmatchedWord) {
		return this.hasUnmatchedWords()
				? this.getUnmatchedWords() + " " + nextUnmatchedWord
				: nextUnmatchedWord;
	}

	public Collection<CodeMatchResult<I>> determinePossibleResults(String nextUnmatchedWord, I extraInfo);
}
