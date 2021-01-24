package info.codesaway.dyce.grammar.java;

import static info.codesaway.dyce.grammar.DYCEGrammarUtilities.determineMethodsForType;
import static info.codesaway.dyce.grammar.DYCEGrammarUtilities.determineSimilarities;
import static info.codesaway.dyce.grammar.DYCEGrammarUtilities.determineVariableName;
import static java.util.stream.Collectors.toMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.stream.Stream;

import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;

import info.codesaway.bex.Indexed;
import info.codesaway.dyce.grammar.CodeMatch;
import info.codesaway.dyce.grammar.CodeMatchInfo;
import info.codesaway.dyce.grammar.CodeMatchResult;
import info.codesaway.dyce.grammar.MethodMatch;
import info.codesaway.dyce.grammar.VariableMatch;

public class JavaChainCodeMatchResult implements CodeMatchResult<CodeMatchInfo> {
	private final int wordCount;
	private final List<CodeMatch> matches;
	private final int score;
	private final String unmatchedWords;

	public static final JavaChainCodeMatchResult EMPTY = new JavaChainCodeMatchResult(0, Collections.emptyList(), 0);

	/**
	 *
	 * @param wordCount
	 * @param matches the matches (must already be immutable / unmodifiable when passed)
	 * @param score
	 */
	private JavaChainCodeMatchResult(final int wordCount, final List<CodeMatch> matches, final int score) {
		this(wordCount, matches, score, "");
	}

	/**
	 *
	 * @param wordCount
	 * @param matches the matches (must already be immutable / unmodifiable when passed)
	 * @param score
	 */
	private JavaChainCodeMatchResult(final int wordCount, final List<CodeMatch> matches, final int score,
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

		StringJoiner code = new StringJoiner(".");
		for (CodeMatch match : this.matches) {
			code.add(match.getCode());
		}

		return code.toString();
	}

	private List<CodeMatch> getMatches() {
		return this.matches;
	}

	public CodeMatch getLastMatch() {
		return this.matches.isEmpty() ? null : this.matches.get(this.matches.size() - 1);
	}

	@Override
	public String getUnmatchedWords() {
		return this.unmatchedWords;
	}

	@Override
	public ITypeBinding getReturnType() {
		return this.matches.isEmpty() ? null : this.getLastMatch().getReturnType();
	}

	/**
	 * Creates a new result which adds the specified match
	 *
	 * @param match the match to add to the new result
	 * @return a new CodeMatchResult containing this CodeMatchResult's matches and the specified <code>match</code> added to the end
	 */
	public CodeMatchResult<CodeMatchInfo> addMatch(final CodeMatch match) {
		return this.addMatch(match, 1);
	}

	/**
	 * Creates a new result which adds the specified match
	 *
	 * @param match the match to add to the new result
	 * @param consumedWordCount the number of words consumed as part of this match
	 * @return a new CodeMatchResult containing this CodeMatchResult's matches and the specified <code>match</code> added to the end
	 */
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
		return new JavaChainCodeMatchResult(wordCount, matches, score);
	}

	@Override
	public CodeMatchResult<CodeMatchInfo> addUnmatchedWord(final String unmatchedWord) {
		int wordCount = this.getWordCount() + 1;
		List<CodeMatch> matches = this.getMatches();
		int score = this.getScore();
		String unmatchedWords = this.getUnmatchedWords(unmatchedWord);

		return new JavaChainCodeMatchResult(wordCount, matches, score, unmatchedWords);
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

		if (!this.hasUnmatchedWords()) {
			// TODO: differentiate declare versus assign
			// Declare will create a new variable
			// Assign will assign to an existing non-final variable (of the same type) and create one if couldn't find any matches
			// TODO: add support for assigning to field
			if (nextUnmatchedWord.equals("declare")) {
				results.add(new JavaAssignmentCodeMatchResult(this, "", this.getWordCount() + 1));
			} else if (nextUnmatchedWord.equals("assign")) {
				results.add(new JavaAssignmentCodeMatchResult(this, "", this.getWordCount() + 1));
			}
		}

		String unmatchedWords = this.getUnmatchedWords(nextUnmatchedWord);

		this.checkForVariableMatch(results, unmatchedWords);
		this.checkForMethodMatch(results, unmatchedWords);

		return results;
	}

	private void checkForVariableMatch(final Collection<CodeMatchResult<CodeMatchInfo>> results,
			final String unmatchedWords) {
		if (this.getLastMatch() != null) {
			return;
		}

		// TODO: use extra info to store the possible variable names (so don't need to recompute each time)
		VariableMatch variableMatch = determineVariableName(unmatchedWords);

		if (variableMatch.isMatch()) {
			//				System.out.println("VariableMatch: " + variableMatch);
			results.add(this.addMatch(variableMatch));
		}
	}

	private static final int MAX_SIMILAR_METHODS = 2;

	private void checkForMethodMatch(final Collection<CodeMatchResult<CodeMatchInfo>> results,
			final String unmatchedWords) {

		if (this.matches.isEmpty()) {
			// TODO: need to check static and instance methods in current class
			// (NOTE: if in static method, don't include instance methods)
			return;
		}

		ITypeBinding type = this.getLastMatch().getReturnType();
		Collection<IMethodBinding> methods = determineMethodsForType(type);

		Stream<String> suggestions = methods.stream()
				.map(IMethodBinding::getName)
				.distinct();

		List<Indexed<String>> similarity = determineSimilarities(suggestions, unmatchedWords, true);

		if (similarity.isEmpty()) {
			return;
		}

		int highScore = similarity.get(0).getIndex();

		if (highScore == 0) {
			return;
		}

		similarity.removeIf(s -> s.getIndex() != highScore);

		if (similarity.isEmpty()) {
			return;
		}

		if (similarity.size() <= MAX_SIMILAR_METHODS) {
			Map<String, Indexed<String>> methodMap = similarity.stream()
					.collect(toMap(Indexed::getValue, Function.identity()));

			for (IMethodBinding method : methods) {
				String methodName = method.getName();
				Indexed<String> methodElement = methodMap.get(methodName);

				if (methodElement == null) {
					continue;
				}

				MethodMatch methodMatch = new MethodMatch(methodElement, method);

				results.add(this.addMatch(methodMatch));
			}
		}

		//		System.out.println("Similar methods: ");
		//		similarity.forEach(System.out::println);
	}
}
