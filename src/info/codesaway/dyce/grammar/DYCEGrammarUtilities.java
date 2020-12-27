package info.codesaway.dyce.grammar;

import static info.codesaway.bex.diff.BasicDiffType.INSERT;
import static info.codesaway.bex.util.BEXUtilities.not;
import static info.codesaway.dyce.util.DYCEUtilities.getNodeRange;
import static info.codesaway.dyce.util.EclipseUtilities.getActivePathname;
import static info.codesaway.dyce.util.EclipseUtilities.getDocumentSelection;
import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.Optional;
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
import info.codesaway.bex.IntBEXRange;
import info.codesaway.bex.IntRange;
import info.codesaway.bex.diff.DiffEdit;
import info.codesaway.bex.diff.DiffHelper;
import info.codesaway.bex.diff.DiffLine;
import info.codesaway.bex.diff.DiffUnit;
import info.codesaway.bex.diff.myers.MyersLinearDiff;
import info.codesaway.bex.parsing.BEXParsingLanguage;
import info.codesaway.bex.parsing.BEXString;
import info.codesaway.bex.util.BEXUtilities;
import info.codesaway.dyce.DYCESearch;
import info.codesaway.dyce.DYCESearchResult;
import info.codesaway.dyce.DYCESearchResultEntry;
import info.codesaway.dyce.DYCESettings;
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

	private static final ThreadLocal<Matcher> COMBINE_CAPS = Pattern
			.getThreadLocalMatcher(" (?<!\\b(?i:set|set to|as|declare|assign|of) )(?=[A-Z])");

	// Keep number low, so prevents false positives (matches due to letters matching, like for long names, though many others not matching)
	//	private static final int MAX_DIFFERENCE = 10;
	private static final int MAX_DIFFERENCE = 4;
	private static final int[] FIBONACCI_NUMBERS = getFibonacciNumbers(100);

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

		System.out.println("BEXString: " + bexString);

		for (DYCEGrammarRule grammarRule : grammarRules) {
			if (!grammarRule.accept(bexString)) {
				continue;
			}

			String result = grammarRule.replace(bexString);
			bexString = new BEXString(result, BEXParsingLanguage.TEXT);
			// TODO: change abbreviated forms, such as "acct" to logical form, account
			System.out.println(result);
		}

		System.out.println("Final result: " + bexString);
		return bexString.getText();
	}

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

		// TODO: use LCS to determine which class name or variable name is closest
		// Start with just local variables
		// Use imports to determine potential class names
		// Can then build over time

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

	public static String determineVariableName(final String variableNameText) {
		// TODO: refactor this and determineClassName, so can get imports and variable names at once
		// (so don't need to parse twice)
		System.out.println("Determine variable name: " + variableNameText);
		Indexed<Optional<String>> activePathname = getActivePathname();

		if (!activePathname.getValue().isPresent()) {
			// Cannot determine Active file, so just return the passed text
			return variableNameText;
		}

		DocumentSelection documentSelection = getDocumentSelection();

		if (!documentSelection.hasOffset()) {
			return variableNameText;
		}

		ASTNode astNode;
		try {
			astNode = DYCEUtilities.createAST(Paths.get(activePathname.getValue().get()));
		} catch (IOException e) {
			// If cannot read the file (should never happen), just return the passed text
			return variableNameText;
		}

		if (!(astNode instanceof CompilationUnit)) {
			// astNode should be a CompilationUnit, since parsed the active file
			return variableNameText;
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

					variables.add(new VariableScope(variableName, position, scope));
				}

				return true;
			}

			@Override
			public boolean visit(final VariableDeclarationFragment node) {
				Optional<IntRange> optionalScope = this.variableScopes.stream()
						.filter(v -> v.contains(node.getName().getStartPosition()))
						.findFirst();

				if (!optionalScope.isPresent()) {
					System.out.println(
							"No scope for variable " + node.getName().getIdentifier() + "\t" + node.getStartPosition());
					// Should never happen
					return true;
				}

				String variableName = node.getName().getIdentifier();

				//				System.out.println("Found variable: " + variableName);

				int position = node.getName().getStartPosition();

				IntRange scope = optionalScope.get();
				variables.add(new VariableScope(variableName, position, scope));

				IVariableBinding variableBinding = node.resolveBinding();

				if (variableBinding != null) {
					// For example, ArrayList doesn't declare stream method (in List interface)
					System.out.println("Methods for variable " + variableBinding.getName());

					// TODO: track each type and determine the methods
					// Index this, since fairly static
					// https://stackoverflow.com/a/36964588
					List<ITypeBinding> bindings = new ArrayList<>();

					ArrayDeque<ITypeBinding> bindingsToProcess = new ArrayDeque<>();
					bindingsToProcess.add(variableBinding.getType());

					Set<String> methodNames = new TreeSet<>();

					do {
						ITypeBinding typeBinding = bindingsToProcess.removeLast();

						if (bindings.stream().anyMatch(b -> typeBinding.isEqualTo(b))) {
							// Already processed
							continue;
						}

						bindings.add(typeBinding);

						// Add super class / interface(s) to be processed
						ITypeBinding superclass = typeBinding.getSuperclass();

						if (superclass != null) {
							bindingsToProcess.add(superclass);
						}

						ITypeBinding[] interfaces = typeBinding.getInterfaces();

						for (ITypeBinding interfaceBinding : interfaces) {
							bindingsToProcess.add(interfaceBinding);
						}

						//						System.out.println("Methods of type: " + typeBinding.getName());
						Stream.of(typeBinding.getDeclaredMethods())
								// Ignore constructors
								.filter(not(IMethodBinding::isConstructor))
								// Ignore static methods, since wouldn't invoke using variable
								.filter(m -> !Modifier.isStatic(m.getModifiers()))
								// TODO: only include private, protected, or package protected in certain circumstances
								// The goal is to only show methods which could be invoked based on the current scope
								.filter(m -> Modifier.isPublic(m.getModifiers()))
								.map(IMethodBinding::getName)
								.forEach(methodNames::add);
						//								.forEach(m -> System.out.println("Method: " + m));

					} while (!bindingsToProcess.isEmpty());

					System.out.println("Method names for variable: " + variableBinding.getName());
					methodNames.forEach(System.out::println);

					String methodNameText = "steam";

					// Determine similarity

					// TODO: replace with comparators added to BEX (issue #113)
					Comparator<Indexed<String>> comparator = Comparator.comparingInt(Indexed::getIndex);
					// Done separately, since compiler gets confused if part of above line
					comparator = comparator.reversed();

					List<Indexed<String>> similarMethodNames = methodNames.stream()
							//							.filter(v -> v.isInScope(offset))
							//							.filter(v -> v.isBeforePosition(offset))
							.map(i -> determineSimilarity(i, methodNameText))
							// 0 is usd to represent "not similar"
							.filter(i -> i.getIndex() > 0)
							.sorted(comparator)
							.collect(toList());

					System.out.println("Similar method names: \t" + similarMethodNames.size());
					similarMethodNames.forEach(System.out::println);
				}

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

		// TODO: replace with comparators added to BEX (issue #113)
		Comparator<Indexed<String>> comparator = Comparator.comparingInt(Indexed::getIndex);
		// Done separately, since compiler gets confused if part of above line
		comparator = comparator.reversed();

		int offset = documentSelection.getOffset();

		List<Indexed<String>> similarVariableNames = variables.stream()
				.filter(v -> v.isInScope(offset))
				.filter(v -> v.isBeforePosition(offset))
				.map(VariableScope::getName)
				.map(i -> determineSimilarity(i, variableNameText))
				// 0 is usd to represent "not similar"
				.filter(i -> i.getIndex() > 0)
				.sorted(comparator)
				.collect(toList());

		System.out.println("Similar variable names: \t" + similarVariableNames.size());
		similarVariableNames.forEach(System.out::println);

		// TODO: also keep track of the package name, in case need to add import
		// Also, when giving choices, could show the package name
		Indexed<String> match = null;

		if (!similarVariableNames.isEmpty()) {
			System.out.println("Similar variable name:");
			similarVariableNames.forEach(System.out::println);

			match = similarVariableNames.get(0);
			System.out.println("Matching variable name: " + match);
		}

		return variableNameText;
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

		// TODO: replace with comparators added to BEX (issue #113)
		Comparator<Indexed<String>> comparator = Comparator.comparingInt(Indexed::getIndex);
		// Done separately, since compiler gets confused if part of above line
		comparator = comparator.reversed();

		List<Indexed<String>> similarImportNames = imports.stream()
				.map(i -> determineSimilarity(i, classNameText))
				// 0 is usd to represent "not similar"
				.filter(i -> i.getIndex() > 0)
				.sorted(comparator)
				.collect(toList());

		// TODO: also keep track of the package name, in case need to add import
		// Also, when giving choices, could show the package name
		Indexed<String> match = null;

		if (!similarImportNames.isEmpty()) {
			System.out.println("Similar import:");
			similarImportNames.forEach(System.out::println);

			match = similarImportNames.get(0);
			System.out.println("Matching import: " + match);
		}

		//		NavigableMap<Integer, NavigableMap<Integer, List<String>>> orderSimilarNames = new TreeMap<>();
		//		if (similarNames.isEmpty()) {
		//		System.out.println("Found no similar looking at imports!");
		// Since none are similar, open up to ANY class name and also import
		DYCESearch search = new DYCESearch(
				"class:(" + classNameText + ") AND (type:class type:interface type:enum)", 1000,
				DYCESettings.getSearcherWorkspace(), Operator.OR);

		//		DYCESearch search = new DYCESearch(
		//				"class:(" + classNameText + ") AND (type:class type:interface type:enum)", 0,
		//				false, 1000,
		//				Optional.empty(), DYCESettings.getSearcherWorkspace(), Operator.OR, false);
		try {
			DYCESearchResult searchResult = DYCESearchJob.search(search, null);

			List<DYCESearchResultEntry> results = searchResult.getResults();

			if (results.isEmpty()) {
				System.out.println("No results!");
			}

			//				for (DYCESearchResultEntry result : results) {
			//					System.out.println("Result: " + result.getClassName());
			//				}

			List<Indexed<String>> similarSearchedNames = searchResult.getResults()
					.stream()
					.map(DYCESearchResultEntry::getClassName)
					.map(name -> determineSimilarity(name, classNameText, true))
					// 0 is usd to represent "not similar"
					.filter(i -> i.getIndex() > 0)
					.sorted(comparator)
					.collect(toList());

			if (match == null && similarSearchedNames.isEmpty()) {
				return classNameText;
			} else if (!similarSearchedNames.isEmpty()) {
				Indexed<String> otherMatch = similarSearchedNames.get(0);

				if (match == null) {
					match = otherMatch;
				} else if (otherMatch.getIndex() > match.getIndex()) {
					// If better match, use it
					// (if has same scope, use existing match, based on imports
					match = otherMatch;
				}
			}
		} catch (IOException | QueryNodeException | ParseException e) {
			return classNameText;
		}
		//		}

		//		for (Indexed<String> name : similarNames) {
		//			NavigableMap<Integer, List<String>> secondLevelMap = orderSimilarNames.computeIfAbsent(difference,
		//					x -> new TreeMap<>(Comparator.reverseOrder()));
		//			List<String> list = secondLevelMap.computeIfAbsent(name.getIndex(), x -> new ArrayList<>());
		//			list.add(name.getValue());

		//			System.out.printf("Similar: %s\t%d%n", name);
		//		}

		//		if (orderSimilarNames.isEmpty()) {
		//			return classNameText;
		//		}

		// Find the first entry, which will have the least amount of unused characters
		//		NavigableMap<Integer, List<String>> secondLevelMap = orderSimilarNames.firstEntry().getValue();

		// Then, find the first entry, which will have the most number of shared characters (LCS - longest common subsequence)
		//		List<String> list = secondLevelMap.firstEntry().getValue();

		if (match == null) {
			return classNameText;
		}

		System.out.println("Match: " + match);

		String matchValue = match.getValue();

		//		System.out.println("Matches:");
		//		list.forEach(e -> System.out.printf("Found match %s%n", e));

		//		return list.get(0);
		return matchValue;
	}

	/**
	 * Determine similarity and assign a similarity score
	 * @param importDeclaration the import which to check how similar
	 * @param classNameText the class name text to compare against
	 * @return the passed classNameText with the "index" being the similarity score
	 */
	private static Indexed<String> determineSimilarity(final ImportDeclaration importDeclaration,
			final String classNameText) {
		Name name = importDeclaration.getName();
		SimpleName importedClass = name.isSimpleName()
				? (SimpleName) name
				: ((QualifiedName) name).getName();
		String importedClassName = importedClass.getIdentifier();

		return determineSimilarity(importedClassName, classNameText);
	}

	private static Indexed<String> determineSimilarity(final String suggestedClassName, final String classNameText) {
		return determineSimilarity(suggestedClassName, classNameText, false);
	}

	private static Indexed<String> determineSimilarity(final String suggestedClassName, final String classNameText,
			final boolean ignoreDifferences) {
		// TODO: instead of using LcsString, use MyersLinearDiff
		// This way, will correctly get long strings of consecutive characters
		// For example, servlet and servletcontext
		// LCS doesn't correctly get that servlet is continuous
		// Whereas, Myers will
		// This way, the score is applied correctly, since add value based on FIBONACCI number of length
		// (so longer sequences have higher scores)

		//		LcsString lcsString = new LcsString(importedClassName.trim().toLowerCase(), classNameText.trim().toLowerCase());
		//		int lcs = lcsString.lcsLength();

		List<DiffEdit> diff = diff(suggestedClassName, classNameText);

		//		if (suggestedClassName.equals("Comment")) {
		//			System.out.println("Diff details:");
		//			diff.forEach(System.out::println);
		//		}

		int matchingCount = (int) diff.stream().filter(DiffEdit::shouldTreatAsNormalizedEqual).count();

		boolean isSimilar = matchingCount > Math.min(suggestedClassName.length(), classNameText.length()) * 0.5
				&& isEachWordSimilar(diff);

		//		if (importedClassName.equals("WarningMsg")) {
		//			System.out.println("diff()");
		//			lcsString.diff().forEach(System.out::println);
		//			System.out.println("diff0()");
		//			lcsString.diff0().forEach(System.out::println);
		//		}

		int difference = suggestedClassName.length() - matchingCount;

		int score;

		if (!isSimilar || difference > MAX_DIFFERENCE && !ignoreDifferences) {
			score = 0;
		} else {
			// TODO: verify this works as expected - created unit test
			score = Math.round(matchingCount * 0.75f);

			// TODO: should add to score if ignore differences and difference is less than MAX_DIFFERENCE?
			if (!ignoreDifferences || difference < MAX_DIFFERENCE) {
				score += MAX_DIFFERENCE - difference;
			}

			// Find consecutive of the same type
			List<DiffUnit> diffBlocks = DiffHelper.combineToDiffBlocks(diff);

			// Also consider runs of matching characters as part of score
			// For example, 2 runs of 3 matching characters is a better match than 6 individual characters matching
			for (DiffUnit diffUnit : diffBlocks) {
				if (!diffUnit.shouldTreatAsNormalizedEqual()) {
					continue;
				}
				//				if (!diffText.startsWith(" ")) {
				//					// Non-matching characters (added / deleted)
				//					continue;
				//				}

				//				int count = diffText.length() - 1;
				int count = diffUnit.getEdits().size();

				// Use fibonacci numbers, since a run of 6 is better than 2 runs of 3, even though both 6 characters
				if (count > 1 && count < FIBONACCI_NUMBERS.length) {
					score += FIBONACCI_NUMBERS[count];
				} else if (count >= FIBONACCI_NUMBERS.length) {
					// This means there are at least 100 consecutive characters in the match
					// (that's a long name)
					int lastIndex = FIBONACCI_NUMBERS.length - 1;
					score += FIBONACCI_NUMBERS[lastIndex] + FIBONACCI_NUMBERS[lastIndex + 1] + count;
				}
			}
		}

		boolean showDebug = BEXUtilities.in(suggestedClassName, "WarningMsg", "NamingException")
				|| suggestedClassName.contains("Servlet")
				|| suggestedClassName.contains("List")
				|| suggestedClassName.contains("Message")
				|| suggestedClassName.contains("Comment");

		//		if (showDebug && score > 0) {
		if (showDebug) {
			System.out.printf("Similar? %s %d %d: score %d%n", suggestedClassName, matchingCount, difference, score);
		}

		return new IndexedValue<>(score, suggestedClassName);
	}

	private static List<DiffEdit> diff(final String importedClassName, final String classNameText) {
		//		LcsString lcsString = new LcsString(importedClassName.trim().toLowerCase(), classNameText.trim().toLowerCase());

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
		//		boolean isWordSimilar = false;
		int wordSimilar = 0;
		int wordCount = 0;

		for (DiffEdit entry : diff) {
			if (entry.shouldTreatAsNormalizedEqual()) {
				wordSimilar++;
				wordCount++;
				//				isWordSimilar = true;
			} else if (entry.getType() == INSERT && entry.getRightText().equals(" ")) {
				// Doesn't handle cases with abbreviations such as Msg for Message (since only 3 characters, yet message is 7)
				//				boolean isWordSimilar = wordSimilar >= Math.ceil(wordCount * 0.5);
				boolean isWordSimilar = wordSimilar >= Math.floor(wordCount * 0.33) && wordSimilar > 0;

				//				System.out.printf("%d %d%n", wordSimilar, wordCount);
				if (!isWordSimilar) {
					return false;
				}

				wordSimilar = 0;
				wordCount = 0;
				//				isWordSimilar = false;
			} else if (entry.getType() == INSERT) {
				wordCount++;
			}
		}

		//		System.out.printf("%d %d%n", wordSimilar, wordCount);

		//		boolean isWordSimilar = wordSimilar >= Math.ceil(wordCount * 0.5);
		boolean isWordSimilar = wordSimilar >= Math.floor(wordCount * 0.33) && wordSimilar > 0;
		return isWordSimilar;
	}

	//	private static boolean isEachWordSimilar(final LcsString lcsString) {
	//		boolean isWordSimilar = false;
	//
	//		for (LcsDiffEntry<Character> entry : lcsString.diff()) {
	//			if (entry.getType() == LcsDiffType.NONE) {
	//				isWordSimilar = true;
	//			} else if (entry.getType() == LcsDiffType.ADD && entry.getValue() == ' ') {
	//				if (!isWordSimilar) {
	//					return false;
	//				}
	//
	//				isWordSimilar = false;
	//			}
	//		}
	//
	//		return isWordSimilar;
	//	}
}