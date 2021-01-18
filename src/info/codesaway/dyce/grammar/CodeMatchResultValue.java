package info.codesaway.dyce.grammar;

import static info.codesaway.dyce.grammar.DYCEGrammarUtilities.determineVariableName;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.eclipse.jdt.core.dom.ITypeBinding;

public class CodeMatchResultValue implements CodeMatchResult<CodeMatchInfo> {
	private final int wordCount;
	private final List<CodeMatch> matches;
	private final int score;
	private final String unmatchedWords;

	public static final CodeMatchResultValue EMPTY = new CodeMatchResultValue(0, Collections.emptyList(), 0);

	/**
	 *
	 * @param wordCount
	 * @param matches the matches (must already be immutable / unmodifiable when passed)
	 * @param score
	 */
	private CodeMatchResultValue(final int wordCount, final List<CodeMatch> matches, final int score) {
		this(wordCount, matches, score, "");
	}

	/**
	 *
	 * @param wordCount
	 * @param matches the matches (must already be immutable / unmodifiable when passed)
	 * @param score
	 */
	private CodeMatchResultValue(final int wordCount, final List<CodeMatch> matches, final int score,
			final String unmatchedWords) {
		this.wordCount = wordCount;
		this.matches = matches;
		this.score = score;
		this.unmatchedWords = unmatchedWords;
	}

	@Override
	public int getWordCount() {
		return this.wordCount;
	}

	@Override
	public int getScore() {
		return this.score;
	}

	@Override
	public boolean isMatch() {
		return !this.hasUnmatchedWords() && CodeMatchResult.super.isMatch();
	}

	@Override
	public String getCode() {
		if (this.matches.isEmpty()) {
			return "CodeMatchResultValue{EMPTY}";
		}

		// TODO: need to implement
		return this.matches.toString();
	}

	private List<CodeMatch> getMatches() {
		return this.matches;
	}

	@Override
	public String getUnmatchedWords() {
		return this.unmatchedWords;
	}

	@Override
	public ITypeBinding getReturnType() {
		return this.matches.isEmpty() ? null : this.matches.get(this.matches.size() - 1).getReturnType();
	}

	@Override
	public CodeMatchResult<CodeMatchInfo> addMatch(final CodeMatch match, final int consumedWordCount) {
		int wordCount = this.getWordCount() + consumedWordCount;

		List<CodeMatch> matches = new ArrayList<>(this.getMatches().size() + 1);
		matches.addAll(this.getMatches());
		matches.add(match);
		matches = Collections.unmodifiableList(matches);

		int currentScore = this.getScore();

		// Ensure that all elements in matches are actual matches CodeMatch.isMatch
		// 1) If at any point, current score is 0 but matches is not empty, this means at least one element is not an actual match, so keep the score at 0
		// 2) Otherwise, if we're adding a match, add the match's score to the current score (which will be 0 the first pass)
		// 3) Finally, set to 0 (if we're trying to add a match which isn't actually a match)
		int score;
		if (currentScore == 0 && !this.getMatches().isEmpty()) {
			score = 0;
		} else if (match.isMatch()) {
			score = currentScore + match.getScore();
		} else {
			score = 0;
		}

		// TODO: need to handle if it's a VariableMatch then I need to indicate this, so next time, it checks for a method versus checking for a variable name again
		return new CodeMatchResultValue(wordCount, matches, score);
	}

	@Override
	public CodeMatchResult<CodeMatchInfo> addUnmatchedWord(final String unmatchedWord) {
		int wordCount = this.getWordCount() + 1;
		List<CodeMatch> matches = this.getMatches();
		int score = this.getScore();
		String unmatchedWords = this.getUnmatchedWords(unmatchedWord);

		return new CodeMatchResultValue(wordCount, matches, score, unmatchedWords);
	}

	@Override
	public String toString() {
		return this.hasUnmatchedWords()
				? this.getCode() + " | unmatched: " + this.getUnmatchedWords()
				: this.getCode();
	}

	@Override
	public Collection<CodeMatchResult<CodeMatchInfo>> determinePossibleResults(final String nextUnmatchedWord,
			final CodeMatchInfo extraInfo) {

		Collection<CodeMatchResult<CodeMatchInfo>> results = new ArrayList<>();

		// TODO: use extra info to store the possible variable names (so don't need to recompute each time)
		VariableMatch variableMatch = determineVariableName(this.getUnmatchedWords(nextUnmatchedWord));

		if (variableMatch.isMatch()) {
			System.out.println("VariableMatch: " + variableMatch);
			results.add(this.addMatch(variableMatch));
		}

		return results;
	}
}
