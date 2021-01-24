package info.codesaway.dyce.grammar;

import static info.codesaway.bex.BEXSide.LEFT;
import static info.codesaway.bex.BEXSide.RIGHT;
import static info.codesaway.bex.diff.BasicDiffType.INSERT;
import static info.codesaway.bex.util.BEXUtilities.in;
import static info.codesaway.bex.util.BEXUtilities.not;
import static info.codesaway.dyce.util.DYCEUtilities.getNodeRange;
import static info.codesaway.dyce.util.EclipseUtilities.getActivePathname;
import static info.codesaway.dyce.util.EclipseUtilities.getDocumentSelection;
import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.flexible.core.QueryNodeException;
import org.apache.lucene.queryparser.flexible.standard.config.StandardQueryConfigHandler.Operator;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;

import info.codesaway.bex.Indexed;
import info.codesaway.bex.IndexedValue;
import info.codesaway.bex.IntBEXPair;
import info.codesaway.bex.IntBEXRange;
import info.codesaway.bex.IntPair;
import info.codesaway.bex.IntRange;
import info.codesaway.bex.diff.DiffChange;
import info.codesaway.bex.diff.DiffEdit;
import info.codesaway.bex.diff.DiffHelper;
import info.codesaway.bex.diff.DiffLine;
import info.codesaway.bex.diff.DiffType;
import info.codesaway.bex.diff.DiffUnit;
import info.codesaway.bex.diff.myers.MyersLinearDiff;
import info.codesaway.bex.parsing.BEXParsingLanguage;
import info.codesaway.bex.parsing.BEXString;
import info.codesaway.dyce.DYCESearch;
import info.codesaway.dyce.DYCESearchResult;
import info.codesaway.dyce.DYCESearchResultEntry;
import info.codesaway.dyce.DYCESettings;
import info.codesaway.dyce.grammar.java.JavaChainCodeMatchResult;
import info.codesaway.dyce.jobs.DYCESearchJob;
import info.codesaway.dyce.parsing.VariableScope;
import info.codesaway.dyce.util.DYCEUtilities;
import info.codesaway.dyce.util.DocumentSelection;
import info.codesaway.util.regex.Matcher;
import info.codesaway.util.regex.Pattern;

public final class DYCEGrammarUtilities {
	@Deprecated
	private DYCEGrammarUtilities() {
		throw new UnsupportedOperationException();
	}

	private static boolean DEBUG = true;

	private static ITypeBinding OBJECT_BINDING;

	private static final ThreadLocal<Matcher> COMBINE_CAPS = Pattern
			.getThreadLocalMatcher(" (?<!\\b(?i:set|set to|as|declare|assign|of) )(?=[A-Z])");

	// Keep number low, so prevents false positives (matches due to letters matching, like for long names, though many others not matching)
	//	private static final int MAX_DIFFERENCE = 10;
	private static final int MAX_DIFFERENCE = 4;
	private static final int[] FIBONACCI_NUMBERS = getFibonacciNumbers(100);

	private static final Comparator<Indexed<String>> DESCENDING_INDEXED_COMPARATOR = getDescendingIndexedComparator();

	/**
	 * Gets fibonacci numbers starting with 1, 1
	 *
	 * <p>This is done so that index 2 in the returned array is the value 2 and index 3 in the returned array is the value 3
	 * @param count number of fibonacci numbers to calculate
	 * @return
	 */
	private static int[] getFibonacciNumbers(final int count) {
		int[] fibonacciNumbers = new int[count];
		// Start with 1, 1 (real fibonacci numbers start with 0, 1; however, for our needs we start with the next set, 1, 1)
		fibonacciNumbers[0] = 1;
		fibonacciNumbers[1] = 1;

		for (int i = 2; i < count; i++) {
			fibonacciNumbers[i] = fibonacciNumbers[i - 2] + fibonacciNumbers[i - 1];
		}

		return fibonacciNumbers;
	}

	public static int getFibonacciNumber(final int index) {
		if (index < FIBONACCI_NUMBERS.length) {
			return FIBONACCI_NUMBERS[index];
		} else {
			int lastIndex = FIBONACCI_NUMBERS.length - 1;
			return FIBONACCI_NUMBERS[lastIndex] + FIBONACCI_NUMBERS[lastIndex - 1] + index;
		}
	}

	private static Comparator<Indexed<String>> getDescendingIndexedComparator() {
		// TODO: replace with comparators added to BEX (issue #113)
		Comparator<Indexed<String>> comparator = Comparator.comparingInt(Indexed::getIndex);
		// Done separately, since compiler gets confused if part of above line
		comparator = comparator.reversed();

		return comparator;
	}

	public static void addGrammarRule(final List<DYCEGrammarRule> grammarRules, final String bexPattern,
			final String replacement) {
		grammarRules.add(new DYCEGrammarRuleValue(bexPattern, replacement));
	}

	/**
	 *
	 * @param document
	 * @param line 0 based line in the document
	 * @throws BadLocationException
	 */
	public static String convertLineTextToSentence(final IDocument document, final int line)
			throws BadLocationException {
		int lineOffset = document.getLineOffset(line);
		int lineLength = document.getLineLength(line);

		// TODO: implement DYCEGrammarRule which handles this
		// BEXPattern to match code
		// BEXPattern to match verbal
		// TODO: use "standard" psudo code as examples

		List<DYCEGrammarRule> grammarRules = new ArrayList<>();
		addGrammarRule(grammarRules, "for ( :[type:w] :[name:w] : :[iterable] )",
				"for each (:[type]) :[name] in :[iterable]");
		// Special kind of declare for common case of declaring empty object and assigning to variable with same type
		// (TODO: is ambiguous with typical declare if class name starts with "new")
		// (guess not really, since equals is part of normal declare)
		addGrammarRule(grammarRules, ":[ space] :[class:w] :[var:w] = new :[class]()",
				"Declare new :[class] named :[var]");
		addGrammarRule(grammarRules, ":[ space] :[type<>] :[var:w] = :[value]",
				// TODO: don't think equals here makes sense (maybe set to?)
				":[space] Declare :[type] named :[var] set to :[value]");

		addGrammarRule(grammarRules, ":[ space] :[var:w] = :[value]",
				":[space] Assign :[var] set to :[value]");

		addGrammarRule(grammarRules, "new :[class]()", "new :[class]");
		addGrammarRule(grammarRules, "new :[class]<:[?type]>()", "new :[class]");
		addGrammarRule(grammarRules, "List<:[type]>", "list of :[type]");
		addGrammarRule(grammarRules, "if ( :[condition] )", "if :[condition]");

		addGrammarRule(grammarRules, "((:[type:w]) :[var])", "cast :[var] as :[type]");

		// TODO: can handle special cases - such as List.size frequently is checked to determine if empty
		// (for example list.size() != 0 or list.size() > 0 means list is not empty)
		// (whereas list.size() == 0 means list is empty)
		// However, have to be able to confirm that the type is a Collection
		// For example, if it was fruit.size() > 0 this shouldn't be changed to fruit is not empty
		addGrammarRule(grammarRules, ":[var:w].get:[method:w]()", ":[var] get :[method]");
		addGrammarRule(grammarRules, ":[var:w].set:[method:w](:[value])", ":[var] set :[method] set to :[value]");
		addGrammarRule(grammarRules, ":[var:w].is:[method:w]()", ":[var] is :[method]");
		addGrammarRule(grammarRules, ":[var:w].:[method:w]()", ":[var] dot :[method]");
		addGrammarRule(grammarRules, ":[val:w] != null", ":[val] is not null");
		addGrammarRule(grammarRules, ":[val:w] == null", ":[val] is null");
		// Could also be greater than or equals
		addGrammarRule(grammarRules, ":[val:w] >= :[val2:w]", ":[val] is at least :[val2]");
		// Could also be less than or equals
		addGrammarRule(grammarRules, ":[val:w] <= :[val2:w]", ":[val] is at most :[val2]");
		addGrammarRule(grammarRules, ":[val:w] > :[val2:w]", ":[val] is greater than :[val2]");
		addGrammarRule(grammarRules, ":[val:w] < :[val2:w]", ":[val] is less than :[val2]");
		// TODO: needs to be able to handle chains - such as import statement
		addGrammarRule(grammarRules, ":[val:w].:[val2:w]", ":[val] dot :[val2]");
		addGrammarRule(grammarRules, "instanceof", "is instance of");
		// End of line, is end of sentence
		addGrammarRule(grammarRules, ":[~;$]", ".");

		IntBEXRange range = IntBEXRange.of(lineOffset, lineOffset + lineLength);

		BEXString documentBexString = new BEXString(document.get());
		boolean isComment = documentBexString.isComment(range);

		if (isComment) {
			System.out.println("Line is part of a comment");
			return "";
		}

		BEXString bexString = documentBexString.substring(range);

		for (DYCEGrammarRule grammarRule : grammarRules) {
			if (!grammarRule.accept(bexString)) {
				continue;
			}

			String result = grammarRule.replace(bexString);
			bexString = new BEXString(result, BEXParsingLanguage.TEXT);
			// TODO: change abbreviated forms, such as "acct" to logical form, account
			if (DEBUG) {
				System.out.println(result);
			}
		}

		if (DEBUG) {
			System.out.println("Final result: " + bexString);
		}
		return bexString.getText();
	}

	private static final Pattern SPACE_PATTERN = Pattern.compile("\\s++");
	private static final ThreadLocal<Matcher> SPACE_MATCHER = new ThreadLocal<Matcher>()
			.withInitial(() -> SPACE_PATTERN.matcher());

	public static String determineCodeForSentence(final String text) {
		String normalizedText = text.trim();
		boolean endsWithBrace = false;
		boolean endsWithSemicolon = false;

		if (normalizedText.endsWith("{")) {
			normalizedText = normalizedText.substring(0, normalizedText.length() - "{".length());
			endsWithBrace = true;
		}

		if (normalizedText.endsWith(".")) {
			// Change period to semicolon
			normalizedText = normalizedText.substring(0, normalizedText.length() - ".".length());
			endsWithSemicolon = true;
		}

		String[] words = SPACE_PATTERN.split(normalizedText);

		if (DEBUG) {
			System.out.println("Words:");
			Arrays.stream(words).forEach(System.out::println);
		}

		if (words.length == 0) {
			return "";
		}

		// For each word determine the following
		// 1) Is it a variable name / class name / method name
		// 2) Is it a new name or part of the current name

		// 1/18/2021 - change logic slightly to handle both questions at once
		// For each word, Is it a variable name / class name / method name / unmatched word

		// Priority query based on the score in descending order (highest score has highest priority)
		PriorityQueue<CodeMatchResult<CodeMatchInfo>> results = new PriorityQueue<>(
				Comparator.comparingInt(CodeMatchResult<CodeMatchInfo>::getScore).reversed());

		// Start with an empty result
		results.add(JavaChainCodeMatchResult.EMPTY);

		List<VariableScope> possibleVariables = determinePossibleVariables();
		CodeMatchInfo extraInfo = new CodeMatchInfo();

		PriorityQueue<CodeMatchResult<CodeMatchInfo>> finalResults = new PriorityQueue<>(
				Comparator.comparingInt(CodeMatchResult<CodeMatchInfo>::getScore).reversed());

		while (!results.isEmpty()) {
			CodeMatchResult<CodeMatchInfo> result = results.remove();

			int wordIndex = result.getWordCount();

			if (wordIndex == words.length) {
				if (DEBUG) {
					System.out.println("Result is match? " + result.isMatch() + "\t" + result);
				}

				if (result.isMatch()) {
					finalResults.add(result);
				}

				continue;
			}

			String word = words[wordIndex];

			if (DEBUG) {
				System.out.println("Initially: " + result);
				System.out.println("Word: " + word);
			}

			Collection<CodeMatchResult<CodeMatchInfo>> possibleResults = result.determinePossibleResults(word,
					extraInfo);

			if (possibleResults != null && !possibleResults.isEmpty()) {
				results.addAll(possibleResults);
			}

			// Always add a possible result that the word isn't matched
			results.add(result.addUnmatchedWord(word));
		}

		// TODO: what to do if no results matched??
		String code = finalResults.isEmpty() ? text : finalResults.element().getCode();

		if (DEBUG) {
			System.out.println("Final results:");
			while (!finalResults.isEmpty()) {
				CodeMatchResult<CodeMatchInfo> result = finalResults.remove();
				System.out.printf("%d\t%s%n", result.getScore(), result);
			}
		}

		String result = code
				+ (endsWithBrace ? "{" : "")
				+ (endsWithSemicolon ? ";" : "");

		return result;
	}

	// TODO: may not be needed / refactored, since first want to determine the code based on variable / method names
	public static String convertSentenceToCode(final String text) {

		String normalizedText = text.trim();
		boolean endsWithBrace = false;
		boolean endsWithSemicolon = false;

		if (normalizedText.endsWith("{")) {
			normalizedText = normalizedText.substring(0, normalizedText.length() - "{".length());
			endsWithBrace = true;
		}

		if (normalizedText.endsWith(".")) {
			// Change period to semicolon
			normalizedText = normalizedText.substring(0, normalizedText.length() - ".".length());
			endsWithSemicolon = true;
		}

		normalizedText = COMBINE_CAPS.get().reset(normalizedText).replaceAll("");

		// Preprocess to combine capitalized parts
		// For now, can use to workaround since not doing lookup by type

		// TODO: see how to reuse majority of grammar for converting code to sentence

		// Iterate in reverse order from grammar rules in convertLineTextToSentence
		// Also need to swap replacement / pattern
		// (some tweaks are needed to get to fully work)
		// TODO: write method to get grammar rules, which handles the details and caches the resulting rules (both directions)

		List<DYCEGrammarRule> grammarRules = new ArrayList<>();
		//		addGrammarRule(grammarRules, "for ( :[type:w] :[name:w] : :[iterable] )",
		//				"for each (:[type]) :[name] in :[iterable]");
		//		// Special kind of declare for common case of declaring empty object and assigning to variable with same type
		//		// (TODO: is ambiguous with typical declare if class name starts with "new")
		//		// (guess not really, since equals is part of normal declare)
		//		addGrammarRule(grammarRules, ":[ space] :[class:w] :[var:w] = new :[class]()",
		//				"Declare new :[class] named :[var]");
		addGrammarRule(grammarRules, ":[? space]:[~[Dd]]eclare :[type<>] named :[var:w] :[~set to|equals] :[value]",
				":[space]:[type] :[var] = :[value]");

		addGrammarRule(grammarRules, ":[? space]:[~[Aa]]ssign :[var:w] :[~set to|equals] :[value]",
				":[space]:[var] = :[value]");

		//		addGrammarRule(grammarRules, "new :[class]()", "new :[class]");
		//		addGrammarRule(grammarRules, "new :[class]<:[?type]>()", "new :[class]");
		//		addGrammarRule(grammarRules, "List<:[type]>", "list of :[type]");
		addGrammarRule(grammarRules, "if :[condition]", "if (:[condition])");

		addGrammarRule(grammarRules, "cast :[var] as :[type:w]", "((:[type]) :[var])");
		//
		//		// TODO: can handle special cases - such as List.size frequently is checked to determine if empty
		//		// (for example list.size() != 0 or list.size() > 0 means list is not empty)
		//		// (whereas list.size() == 0 means list is empty)
		//		// However, have to be able to confirm that the type is a Collection
		//		// For example, if it was fruit.size() > 0 this shouldn't be changed to fruit is not empty
		//		addGrammarRule(grammarRules, ":[var:w].get:[method:w]()", ":[var] get :[method]");
		addGrammarRule(grammarRules, ":[var:w] set :[method:w] set to :[value]", ":[var].set:[method](:[value])");
		//		addGrammarRule(grammarRules, ":[var:w].is:[method:w]()", ":[var] is :[method]");
		//		addGrammarRule(grammarRules, ":[var:w].:[method:w]()", ":[var] dot :[method]");
		//		addGrammarRule(grammarRules, ":[val:w] != null", ":[val] is not null");
		//		addGrammarRule(grammarRules, ":[val:w] == null", ":[val] is null");
		//		// Could also be greater than or equals
		//		addGrammarRule(grammarRules, ":[val:w] >= :[val2:w]", ":[val] is at least :[val2]");
		//		// Could also be less than or equals
		//		addGrammarRule(grammarRules, ":[val:w] <= :[val2:w]", ":[val] is at most :[val2]");
		//		addGrammarRule(grammarRules, ":[val:w] > :[val2:w]", ":[val] is greater than :[val2]");
		//		addGrammarRule(grammarRules, ":[val:w] < :[val2:w]", ":[val] is less than :[val2]");
		//		addGrammarRule(grammarRules, ":[val:w].:[val2:w]", ":[val] dot :[val2]");
		addGrammarRule(grammarRules, "is instance of", "instanceof");

		// Reverse the rules, since should go opposite order as convertLineTextToSentence
		Collections.reverse(grammarRules);

		System.out.println("Normalized text: " + normalizedText);

		//		IntBEXRange range = IntBEXRange.of(lineOffset, lineOffset + lineLength);

		//		BEXString documentBexString = new BEXString(document.get());
		//		boolean isComment = documentBexString.isComment(range);
		//
		//		if (isComment) {
		//			System.out.println("Line is part of a comment");
		//			return;
		//		}

		//		BEXString bexString = documentBexString.substring(range);
		BEXString bexString = new BEXString(normalizedText, BEXParsingLanguage.TEXT);

		System.out.println("In convertSentenceToCode, BEXString: " + bexString);

		for (DYCEGrammarRule grammarRule : grammarRules) {
			if (!grammarRule.accept(bexString)) {
				continue;
			}

			String result = grammarRule.replace(bexString);
			bexString = new BEXString(result, BEXParsingLanguage.TEXT);
			// TODO: change abbreviated forms, such as "acct" to logical form, account
			System.out.println(result);
		}

		String result = bexString.getText()
				+ (endsWithBrace ? "{" : "")
				+ (endsWithSemicolon ? ";" : "");

		System.out.println("Final result: " + result);
		return result;
	}

	public static List<VariableScope> determinePossibleVariables() {
		Indexed<Optional<String>> activePathname = getActivePathname();

		if (!activePathname.getValue().isPresent()) {
			// Cannot determine Active file
			return Collections.emptyList();
		}

		DocumentSelection documentSelection = getDocumentSelection();

		if (!documentSelection.hasOffset()) {
			return Collections.emptyList();
		}

		ASTNode astNode;
		try {
			astNode = DYCEUtilities.createAST(Paths.get(activePathname.getValue().get()));
		} catch (IOException e) {
			// If cannot read the file (should never happen), just return the passed text
			return Collections.emptyList();
		}

		if (OBJECT_BINDING == null) {
			OBJECT_BINDING = astNode.getAST().resolveWellKnownType("java.lang.Object");
		}

		if (!(astNode instanceof CompilationUnit)) {
			// astNode should be a CompilationUnit, since parsed the active file
			return Collections.emptyList();
		}

		CompilationUnit compilationUnit = (CompilationUnit) astNode;

		List<VariableScope> variables = new ArrayList<>();

		ASTVisitor visitor = new ASTVisitor() {
			private final NavigableSet<IntRange> variableScopes = new TreeSet<>(
					// Sort in descending order, so narrowest scope would be first (when determining scope of variable)
					Comparator.comparing(IntRange::getStart).reversed());

			@Override
			public boolean visit(final TypeDeclaration node) {
				this.variableScopes.add(getNodeRange(node));
				return true;
			}

			@Override
			public boolean visit(final EnumDeclaration node) {
				this.variableScopes.add(getNodeRange(node));
				return true;
			}

			// TODO: test enums and add what's needed to include these "variables" / "fields"

			@Override
			public boolean visit(final MethodDeclaration node) {
				IntRange scope = getNodeRange(node);
				this.variableScopes.add(scope);

				@SuppressWarnings("unchecked")
				List<SingleVariableDeclaration> parameters = node.parameters();

				for (SingleVariableDeclaration parameter : parameters) {
					String variableName = parameter.getName().getIdentifier();
					int position = parameter.getName().getStartPosition();

					variables.add(new VariableScope(variableName, position, scope, parameter.resolveBinding()));
				}

				return true;
			}

			@Override
			public boolean visit(final VariableDeclarationFragment node) {
				Optional<IntRange> determinedScope = this.variableScopes.stream()
						.filter(v -> v.contains(node.getName().getStartPosition()))
						.findFirst();

				if (!determinedScope.isPresent()) {
					System.out.println(
							"No scope for variable " + node.getName().getIdentifier() + "\t" + node.getStartPosition());
					// Should never happen
					return true;
				}

				String variableName = node.getName().getIdentifier();

				//				System.out.println("Found variable: " + variableName);

				int position = node.getName().getStartPosition();

				IntRange scope = determinedScope.get();
				variables.add(new VariableScope(variableName, position, scope, node.resolveBinding()));

				//				IVariableBinding variableBinding = node.resolveBinding();
				//
				//				if (variableBinding != null) {
				//					// For example, ArrayList doesn't declare stream method (in List interface)
				//					System.out.println("Methods for variable " + variableBinding.getName());
				//
				//					Collection<String> methodNames = determineMethodsForType(variableBinding.getType());
				//
				//					System.out.println("Method names for variable: " + variableBinding.getName());
				//					methodNames.forEach(System.out::println);
				//
				//					String methodNameText = "steam";
				//
				//					// Determine similarity
				//					List<Indexed<String>> similarMethodNames = determineSimilarity(methodNames.stream(),
				//							methodNameText);
				//
				//					System.out.println("Similar method names: \t" + similarMethodNames.size());
				//					similarMethodNames.forEach(System.out::println);
				//				}

				return true;
			}

			// Note: block starts with '{' and ends with '}'
			// Note: Certain constructs contain variable declarations and blocks
			// * for loop
			// * try-with-resource
			// * Others??
			// IMPORTANT: if the brace is on the next line, that's where the block officially starts
			// However, these declared variables are part of that block
			@Override
			public boolean visit(final Block node) {
				this.variableScopes.add(getNodeRange(node));
				return true;
			}

			@Override
			public boolean visit(final ForStatement node) {
				this.variableScopes.add(getNodeRange(node));
				return true;
			}

			@Override
			public boolean visit(final TryStatement node) {
				this.variableScopes.add(getNodeRange(node));
				return true;
			}
		};

		compilationUnit.accept(visitor);

		int offset = documentSelection.getOffset();

		List<VariableScope> possibleVariables = variables.stream()
				.filter(v -> v.isInScope(offset))
				.filter(v -> v.isBeforePosition(offset))
				.collect(toList());

		return possibleVariables;
	}

	public static VariableMatch determineVariableName(final String variableNameText) {
		return determineVariableName(variableNameText, determinePossibleVariables());
	}

	public static VariableMatch determineVariableName(final String variableNameText,
			final List<VariableScope> possibleVariables) {
		// TODO: refactor this and determineClassName, so can get imports and variable names at once
		// (so don't need to parse twice)

		// TODO: refactor this to determine variables in scope
		// (that way, can check multiple passes for matches without parsing file again)

		if (DEBUG) {
			System.out.println("Determine variable name: " + variableNameText);
		}

		if (possibleVariables.isEmpty()) {
			return new VariableMatch(variableNameText);
		}

		Stream<String> suggestions = possibleVariables.stream()
				.map(VariableScope::getName);

		List<Indexed<String>> similarVariableNames = determineSimilarities(suggestions, variableNameText);

		if (DEBUG) {
			System.out.println("Similar variable names: \t" + similarVariableNames.size());
			similarVariableNames.forEach(System.out::println);
		}

		// TODO: also keep track of the package name, in case need to add import
		// Also, when giving choices, could show the package name
		Indexed<String> match = null;

		if (!similarVariableNames.isEmpty()) {
			if (DEBUG) {
				System.out.println("Similar variable name:");
				similarVariableNames.forEach(System.out::println);
			}

			match = similarVariableNames.get(0);
			if (DEBUG) {
				System.out.println("Matching variable name: " + match);
			}
		}

		if (match == null) {
			return new VariableMatch(variableNameText);
		}

		// Added variable for lambda
		Indexed<String> finalMatch = match;

		Optional<VariableScope> variable = possibleVariables.stream()
				.filter(v -> v.getName().equals(finalMatch.getValue()))
				.findFirst();

		IVariableBinding binding = variable.isPresent()
				? variable.get().getBinding()
				: null;

		return new VariableMatch(match, binding);
	}

	public static Collection<IMethodBinding> determineMethodsForType(final ITypeBinding type) {
		if (type == null) {
			return Collections.emptyList();
		}

		// TODO: limit to ones in scope, based on current cursor position

		// TODO: track each type and determine the methods
		// Index this, since fairly static
		// https://stackoverflow.com/a/36964588
		List<ITypeBinding> bindingsAlreadyProcessed = new ArrayList<>();
		ArrayDeque<ITypeBinding> bindingsToProcess = new ArrayDeque<>();
		bindingsToProcess.add(type);
		bindingsToProcess.add(OBJECT_BINDING);

		Set<IMethodBinding> methods = new TreeSet<>(Comparator.comparing(IMethodBinding::getName));

		do {
			ITypeBinding typeBinding = bindingsToProcess.removeLast();

			if (bindingsAlreadyProcessed.stream().anyMatch(b -> typeBinding.isEqualTo(b))) {
				// Already processed
				continue;
			}

			bindingsAlreadyProcessed.add(typeBinding);

			// Add super class / interface(s) to be processed
			ITypeBinding superclass = typeBinding.getSuperclass();

			if (superclass != null) {
				bindingsToProcess.add(superclass);
			}

			ITypeBinding[] interfaces = typeBinding.getInterfaces();

			for (ITypeBinding interfaceBinding : interfaces) {
				bindingsToProcess.add(interfaceBinding);
			}

			Stream.of(typeBinding.getDeclaredMethods())
					// Ignore constructors
					.filter(not(IMethodBinding::isConstructor))
					// Ignore static methods, since wouldn't invoke using variable
					.filter(m -> !Modifier.isStatic(m.getModifiers()))
					// TODO: only include private, protected, or package protected in certain circumstances
					// The goal is to only show methods which could be invoked based on the current scope
					.filter(m -> Modifier.isPublic(m.getModifiers()))
					//					.map(IMethodBinding::getName)
					.forEach(methods::add);

		} while (!bindingsToProcess.isEmpty());

		return methods;
	}

	public static String determineClassName(final String classNameText) {
		System.out.println("Determine class name: " + classNameText);
		Indexed<Optional<String>> activePathname = getActivePathname();

		if (!activePathname.getValue().isPresent()) {
			// Cannot determine Active file, so just return the passed text
			return classNameText;
		}

		ASTNode astNode;
		try {
			astNode = DYCEUtilities.createAST(Paths.get(activePathname.getValue().get()));
		} catch (IOException e) {
			// If cannot read the file (should never happen), just return the passed text
			return classNameText;
		}

		if (!(astNode instanceof CompilationUnit)) {
			// astNode should be a CompilationUnit, since parsed the active file
			return classNameText;
		}

		CompilationUnit compilationUnit = (CompilationUnit) astNode;
		@SuppressWarnings("unchecked")
		List<ImportDeclaration> imports = compilationUnit.imports();

		List<Indexed<String>> similarImportNames = determineSimilarities(imports.stream()
				.map(DYCEGrammarUtilities::getImportedClassName), classNameText);

		// TODO: also keep track of the package name, in case need to add import
		// Also, when giving choices, could show the package name
		Indexed<String> match = null;

		if (!similarImportNames.isEmpty()) {
			System.out.println("Similar import:");
			similarImportNames.forEach(System.out::println);

			match = similarImportNames.get(0);
			System.out.println("Matching import: " + match);
		}

		// Open up to ANY class name and also import
		DYCESearch search = new DYCESearch(
				"class:(" + classNameText + ") AND (type:class type:interface type:enum)", 1000,
				DYCESettings.getSearcherWorkspace(), Operator.OR);

		try {
			DYCESearchResult searchResult = DYCESearchJob.search(search, null);

			List<DYCESearchResultEntry> results = searchResult.getResults();

			if (results.isEmpty()) {
				System.out.println("No results!");
			}

			Stream<String> suggestions = searchResult.getResults()
					.stream()
					.map(DYCESearchResultEntry::getClassName);

			List<Indexed<String>> similarSearchedNames = determineSimilarities(suggestions, classNameText, true);

			if (match == null && similarSearchedNames.isEmpty()) {
				return classNameText;
			} else if (!similarSearchedNames.isEmpty()) {
				Indexed<String> otherMatch = similarSearchedNames.get(0);

				if (match == null) {
					match = otherMatch;
				} else if (otherMatch.getIndex() > match.getIndex()) {
					// If better match, use it
					// (if has same score, use existing match, based on existing imports
					match = otherMatch;
				}
			}
		} catch (IOException | QueryNodeException | ParseException e) {
			return classNameText;
		}

		if (match == null) {
			return classNameText;
		}

		System.out.println("Match: " + match);

		String matchValue = match.getValue();

		return matchValue;
	}

	private static String getImportedClassName(final ImportDeclaration importDeclaration) {
		Name name = importDeclaration.getName();
		SimpleName importedClass = name.isSimpleName()
				? (SimpleName) name
				: ((QualifiedName) name).getName();

		return importedClass.getIdentifier();
	}

	public static List<Indexed<String>> determineSimilarities(final Stream<String> suggestions,
			final String inputText) {
		return determineSimilarities(suggestions, inputText, false);
	}

	public static List<Indexed<String>> determineSimilarities(final Stream<String> suggestions, final String inputText,
			final boolean ignoreDifferences) {
		return suggestions
				.map(i -> determineSimilarity(i, inputText, ignoreDifferences))
				// 0 is used to represent "not similar"
				.filter(i -> i.getIndex() > 0)
				.sorted(DESCENDING_INDEXED_COMPARATOR)
				.collect(toList());
	}

	/**
	 * Determine similarity and assign a similarity score
	 * @param suggestion the suggestion
	 * @param inputText the inputted text to compare against
	 * @return the passed inputted text with the "index" being the similarity score
	 */
	public static Indexed<String> determineSimilarity(final String suggestion, final String inputText,
			final boolean ignoreDifferences) {

		// Use Myers diff to determine corresponding parts
		// * Preferred over LCS since Myers is better at finding continuous ranges of matching characters
		// * These continuous ranges result in higher scores
		//   	Since 6 consecutive matching characters is preferred to
		//   	6 separate matching characters
		List<DiffEdit> diff = diff(suggestion, inputText);

		int matchingCount = (int) diff.stream().filter(DiffEdit::shouldTreatAsNormalizedEqual).count();

		boolean isSimilar = matchingCount > Math.min(suggestion.length(), inputText.length()) * 0.5
				&& isEachWordSimilar(diff);

		int difference = suggestion.length() - matchingCount;

		if (DEBUG) {
			System.out.println("Suggestion: " + suggestion + "\t" + inputText);
		}

		int score;

		if (!isSimilar || difference > MAX_DIFFERENCE && !ignoreDifferences) {
			score = 0;
		} else {
			// TODO: verify this works as expected - create unit test
			score = Math.round(matchingCount * 0.75f);

			// TODO: should add to score if ignore differences and difference is less than MAX_DIFFERENCE?
			if (!ignoreDifferences || difference < MAX_DIFFERENCE) {
				score += MAX_DIFFERENCE - difference;
			}

			// Find consecutive of the same type
			List<DiffEdit> filteredDiff = new ArrayList<>(diff);
			filteredDiff.removeIf(e -> in(e.getText(), " ", "_"));

			List<DiffUnit> diffBlocks = DiffHelper.combineToDiffBlocks(filteredDiff);

			// Combine into changes (logic based on RangeComparatorBEX class in BEX plugin)
			// In this case, combining blocks that should be treated as normalized equal where either the left side or right side is continuous
			List<DiffChange<String>> changes = new ArrayList<>();
			List<DiffUnit> currentChanges = new ArrayList<>();
			DiffType currentChangeType = null;
			IntPair currentEnd = IntBEXPair.ZERO;

			for (DiffUnit diffBlock : diffBlocks) {
				DiffType type = diffBlock.getType();
				List<DiffEdit> edits = diffBlock.getEdits();
				IntBEXPair blockStart = edits.get(0).getLineNumber();

				boolean isConsecutive = DiffHelper.isConsecutive(LEFT, currentEnd, blockStart, false)
						|| DiffHelper.isConsecutive(RIGHT, currentEnd, blockStart, false);
				boolean partOfCurrentChanges = Objects.equals(type, currentChangeType)
						&& type.shouldTreatAsNormalizedEqual()
						&& isConsecutive;

				if (partOfCurrentChanges) {
					currentChanges.add(diffBlock);
				} else {
					if (!currentChanges.isEmpty()) {
						// TODO: could use info as debugging info?
						String info = "";
						changes.add(new DiffChange<>(currentChangeType, currentChanges, info));
						currentChanges.clear();
					}

					// Reset values
					currentChangeType = type;
					currentChanges.add(diffBlock);
					currentEnd = edits.get(edits.size() - 1).getLineNumber();
				}
			}

			if (!currentChanges.isEmpty()) {
				// TODO: could use info as debugging info?
				String info = "";
				changes.add(new DiffChange<>(currentChangeType, currentChanges, info));
				currentChanges.clear();
			}

			//			if (DEBUG) {
			//				for (int i = 0; i < changes.size(); i++) {
			//					System.out.println("Change " + (i + 1));
			//					changes.get(i).getEdits().forEach(System.out::println);
			//				}
			//			}

			// Also consider runs of matching characters as part of score
			// For example, 2 runs of 3 matching characters is a better match than 6 individual characters matching
			for (DiffUnit diffUnit : changes) {
				if (!diffUnit.shouldTreatAsNormalizedEqual()) {
					continue;
				}
				int count = diffUnit.getEdits().size();

				// Use fibonacci numbers, since a run of 6 is better than 2 runs of 3, even though both 6 characters
				if (count > 1) {
					score += getFibonacciNumber(count);
				}
			}
		}

		if (DEBUG) {
			System.out.printf("Similar? %s %d %d: score %d%n", suggestion, matchingCount, difference, score);
		}

		return new IndexedValue<>(score, suggestion);
	}

	private static List<DiffEdit> diff(final String importedClassName, final String classNameText) {
		String text1 = importedClassName.trim().toLowerCase();
		String text2 = classNameText.trim().toLowerCase();

		List<DiffLine> lines1 = IntStream.range(0, text1.length())
				.mapToObj(i -> new DiffLine(i, text1.substring(i, i + 1)))
				.collect(toList());
		List<DiffLine> lines2 = IntStream.range(0, text2.length())
				.mapToObj(i -> new DiffLine(i, text2.substring(i, i + 1)))
				.collect(toList());

		return MyersLinearDiff.diff(lines1, lines2);
	}

	private static boolean isEachWordSimilar(final List<DiffEdit> diff) {
		int wordSimilar = 0;
		int wordCount = 0;

		for (DiffEdit entry : diff) {
			if (entry.shouldTreatAsNormalizedEqual()) {
				wordSimilar++;
				wordCount++;
			} else if (entry.getType() == INSERT && entry.getRightText().equals(" ")) {
				boolean isWordSimilar = wordSimilar >= Math.floor(wordCount * 0.33) && wordSimilar > 0;

				if (!isWordSimilar) {
					return false;
				}

				wordSimilar = 0;
				wordCount = 0;
			} else if (entry.getType() == INSERT) {
				wordCount++;
			}
		}

		boolean isWordSimilar = wordSimilar >= Math.floor(wordCount * 0.33) && wordSimilar > 0;
		return isWordSimilar;
	}
}