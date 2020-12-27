package info.codesaway.dyce.util;

import static info.codesaway.util.indexer.IndexerUtilities.addCamelCaseFilter;
import static info.codesaway.util.indexer.IndexerUtilities.addUsualFilters;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.PackageDeclaration;

import info.codesaway.bex.IntBEXRange;
import info.codesaway.bex.IntRange;
import info.codesaway.dyce.Activator;
import info.codesaway.util.indexer.IndexerUtilities;
import info.codesaway.util.indexer.LuceneStep;

public final class DYCEUtilities {
	private DYCEUtilities() {
		throw new UnsupportedOperationException();
	}

	/**
	 *
	 *
	 * @param step Lucene step
	 * @return
	 * @throws IOException
	 */
	public static Analyzer createAnalyzer(final LuceneStep step) throws IOException {
		// Read files from the state location, so can be modified by user
		CustomAnalyzer.Builder builder = CustomAnalyzer.builder(Activator.STATE_LOCATION).withTokenizer("standard");

		// Is this required for index analyzer?? flattenGraph
		// https://lucene.apache.org/solr/guide/6_6/filter-descriptions.html
		//				.addTokenFilter("flattenGraph)
		// https://www.elastic.co/guide/en/elasticsearch/reference/current/analysis-pattern-capture-tokenfilter.html

		//		if (step == LuceneStep.INDEX) {
		//Handle camel case names such as SQLException
		// (so can search for "sql" or "exception")
		// Note: include when query as well
		// (necessary to allow searching for camelCase names which may not be exact and where synonyms can help)

		// TODO: add settings to customize creating analzyer
		// (have options such as camelCase / PascalCase pattern)
		// (this way, user can use if want or specify their own pattern)

		addCamelCaseFilter(builder);

		// https://lucene.apache.org/solr/guide/6_6/filter-descriptions.html#FilterDescriptions-SynonymGraphFilter
		if (step != LuceneStep.INDEX) {
			// Only use SynonymGraphFilter on query
			// (per documentation)
			// An added benefit is that I don't need to reindex when adding synonyms
			// http://blog.vogella.com/2010/07/06/reading-resources-from-plugin/
			// TODO: add support for user specifying files (and allow editing on the preferences page)
			// TODO: implement
			//			builder.addTokenFilter("synonymGraph", "synonyms", "abbreviations.txt");
			//			builder.addTokenFilter("synonymGraph", "synonyms", "synonyms.txt", "ignoreCase", "true");
		}

		addUsualFilters(builder);

		Map<String, Analyzer> analyzerMap = new HashMap<>();
		// Don't want to have stop words for "type" (otherwise filters out "if" and "for")
		// TODO: allow specifying different analyzers for different fields
		analyzerMap.put("type", new StandardAnalyzer(CharArraySet.EMPTY_SET));

		return IndexerUtilities.createAnalyzer(builder, analyzerMap);
	}

	public static ASTNode createAST(final Path path) throws IOException {
		ASTParser parser = ASTParser.newParser(AST.JLS13);
		parser.setResolveBindings(true);
		parser.setKind(ASTParser.K_COMPILATION_UNIT);

		parser.setBindingsRecovery(true);
		// TODO: not sure if needed, but setting
		parser.setStatementsRecovery(true);

		boolean alreadySetSource = false;
		try {
			URI uri = path.toUri();

			// Used to prevent CoreException which checking zip files
			// (in this case, wouldn't expect to find in project)
			// org.eclipse.core.runtime.CoreException: No file system is defined for scheme: jar
			if (!Objects.equals(uri.getScheme(), "jar")) {
				IFile[] files = Activator.WORKSPACE_ROOT.findFilesForLocationURI(uri);

				if (files.length > 0) {
					IProject iProject = files[0].getProject();
					// Set source to compilation unit, so can resolve bindings
					parser.setSource(JavaCore.createCompilationUnitFrom(files[0]));
					alreadySetSource = true;
					// https://stackoverflow.com/a/12521662
					if (iProject != null && iProject.hasNature(JavaCore.NATURE_ID)) {
						IJavaProject javaProject = JavaCore.create(iProject);
						// Not sure what this does, if anything, but no harm keeping
						parser.setProject(javaProject);
					}
				}
			}
		} catch (CoreException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		if (!alreadySetSource) {
			String source = new String(Files.readAllBytes(path));
			parser.setSource(source.toCharArray());
		}

		Map<String, String> options = JavaCore.getOptions();

		// Required to correctly read enums
		// http://help.eclipse.org/kepler/index.jsp?topic=%2Forg.eclipse.jdt.doc.isv%2Freference%2Fapi%2Forg%2Feclipse%2Fjdt%2Fcore%2Fdom%2FASTParser.html
		JavaCore.setComplianceOptions(JavaCore.VERSION_1_8, options);

		parser.setCompilerOptions(options);

		return parser.createAST(null);
	}

	/**
	 * Gets the package name
	 * @param compilationUnit the compilation unit
	 * @return the package name (or <code>null</code> if could not be determined)
	 */
	public static String getPackageName(final CompilationUnit compilationUnit) {
		if (compilationUnit != null) {
			PackageDeclaration packageDeclaration = compilationUnit.getPackage();

			if (packageDeclaration != null) {
				return packageDeclaration.getName().getFullyQualifiedName();
			}
		}

		return null;
	}

	public static IntRange getNodeRange(final ASTNode node) {
		int start = node.getStartPosition();
		int end = start + node.getLength();

		return IntBEXRange.of(start, end);
	}
}
