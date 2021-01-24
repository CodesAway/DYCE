package info.codesaway.dyce.grammar.java;

import static info.codesaway.dyce.grammar.DYCEGrammarUtilities.getFibonacciNumber;

import java.util.Collection;
import java.util.Collections;

import org.eclipse.jdt.core.dom.ITypeBinding;

import info.codesaway.dyce.grammar.CodeMatchInfo;
import info.codesaway.dyce.grammar.CodeMatchResult;

public class JavaAssignmentCodeMatchResult implements CodeMatchResult<CodeMatchInfo> {
	private final CodeMatchResult<CodeMatchInfo> codeMatchResult;
	private final String variableName;
	private final int wordCount;

	public JavaAssignmentCodeMatchResult(final CodeMatchResult<CodeMatchInfo> codeMatchResult,
			final String variableName, final int wordCount) {
		this.codeMatchResult = codeMatchResult;
		this.variableName = variableName;
		this.wordCount = wordCount;
	}

	public String getVariableName() {
		return this.variableName;
	}

	@Override
	public int getScore() {
		// Give preference to assignments
		return this.codeMatchResult.getScore() + 2 * getFibonacciNumber(this.variableName.length()) + 100;
	}

	@Override
	public String getCode() {
		// TODO: need to also add import for return type an needed
		// TODO: if variable name is blank give suggestion
		// If method chain, use last part of chain to get suggestion?
		// Could use special variable name "class" to indicate want to use class name as variable name if blank

		return String.format("%s %s = %s;", this.codeMatchResult.getReturnType().getName(), this.getVariableName(),
				this.codeMatchResult);
	}

	@Override
	public ITypeBinding getReturnType() {
		return this.codeMatchResult.getReturnType();
	}

	@Override
	public int getWordCount() {
		return this.wordCount;
	}

	@Override
	public CodeMatchResult<CodeMatchInfo> addUnmatchedWord(final String unmatchedWord) {
		// TODO: remove any invalid characters - such as if said "Amy's list" variable name should be amysList
		String variableName = this.variableName;

		if (variableName.isEmpty()) {
			variableName = unmatchedWord;
		} else {
			String word = unmatchedWord;

			// Upper case first letter - for camel casing
			// TODO: will this work? (what language / context is used??)
			word = Character.toUpperCase(word.charAt(0)) + word.substring(1);

			variableName += word;
		}

		return new JavaAssignmentCodeMatchResult(this.codeMatchResult, variableName,
				this.wordCount + 1);
	}

	@Override
	public String getUnmatchedWords() {
		return this.variableName;
	}

	@Override
	public Collection<CodeMatchResult<CodeMatchInfo>> determinePossibleResults(final String nextUnmatchedWord,
			final CodeMatchInfo extraInfo) {
		return Collections.emptyList();
	}

	@Override
	public String toString() {
		return this.getCode();
	}
}