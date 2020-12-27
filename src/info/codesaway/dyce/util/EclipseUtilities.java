package info.codesaway.dyce.util;

import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import info.codesaway.bex.Indexed;
import info.codesaway.bex.IndexedValue;

public final class EclipseUtilities {
	@Deprecated
	private EclipseUtilities() {
		throw new UnsupportedOperationException();
	}

	private static final Indexed<Optional<String>> EMPTY = new IndexedValue<>(0, Optional.empty());

	public static Optional<IEditorPart> getActiveEditorPart() {
		// https://stackoverflow.com/a/17901551/12610042
		IWorkbench wb = PlatformUI.getWorkbench();
		IWorkbenchWindow window = wb.getActiveWorkbenchWindow();

		if (window == null) {
			return Optional.empty();
		}

		IWorkbenchPage page = window.getActivePage();

		if (page == null) {
			return Optional.empty();
		}

		// 12/7/2020 - simplified logic, based on Eclipse wiki page
		//		IWorkbenchPart workbenchPart = page.getActivePart();
		//
		//		if (workbenchPart == null) {
		//			return Optional.empty();
		//		}
		//
		//		IEditorPart editorPart = workbenchPart.getSite().getPage().getActiveEditor();
		// Logic from https://wiki.eclipse.org/FAQ_How_do_I_insert_text_in_the_active_text_editor%3F

		IEditorPart editorPart = page.getActiveEditor();

		return Optional.ofNullable(editorPart);
	}

	public static Optional<ITextEditor> getActiveTextEditor() {
		Optional<IEditorPart> activeEditorPart = getActiveEditorPart();

		if (!activeEditorPart.isPresent()) {
			return Optional.empty();
		}

		IEditorPart editorPart = activeEditorPart.get();

		if (!(editorPart instanceof ITextEditor)) {
			return Optional.empty();
		}

		ITextEditor editor = (ITextEditor) editorPart;
		return Optional.of(editor);
	}

	public static Optional<IDocument> getActiveDocument() {
		return getActiveDocument(getActiveTextEditor());
	}

	private static Optional<IDocument> getActiveDocument(final Optional<ITextEditor> editor) {
		return editor.isPresent()
				? getActiveDocument(editor.get())
				: Optional.empty();
	}

	public static Optional<IDocument> getActiveDocument(final ITextEditor editor) {
		IDocumentProvider documentProvider = editor.getDocumentProvider();
		return Optional.ofNullable(documentProvider.getDocument(editor.getEditorInput()));
	}

	//	@NonNullByDefault
	@SuppressWarnings({ "null" })
	public static Indexed<Optional<String>> getActivePathname() {
		Optional<IEditorPart> optionalEditorPart = getActiveEditorPart();

		if (!optionalEditorPart.isPresent()) {
			return EMPTY;
		}

		IEditorPart editorPart = optionalEditorPart.get();

		IEditorInput editorInput = editorPart.getEditorInput();

		if (editorInput == null) {
			return EMPTY;
		}

		//		@Nullable
		IFile iFile = editorInput.getAdapter(IFile.class);

		// Not dead code, getAdapter says NonNull,
		// but Javadoc says can return null
		if (iFile == null) {
			return EMPTY;
		}

		// System.out.println("Location: " + iFile.getLocation().toFile());

		// Get absolute path
		IPath iPath = iFile.getLocation();

		if (iPath == null) {
			return EMPTY;
		}

		String pathname = iPath.toFile().toString();

		// https://www.programcreek.com/java-api-examples/?code=trylimits%2FEclipse-Postfix-Code-Completion%2FEclipse-Postfix-Code-Completion-master%2Fluna%2Forg.eclipse.jdt.ui%2Fui%2Forg%2Feclipse%2Fjdt%2Fui%2Ftext%2FJavaSourceViewerConfiguration.java
		// getProject method
		// https://www.eclipse.org/forums/index.php/t/74183/
		// https://www.eclipse.org/forums/index.php/t/85166/
		int currentLine;
		if (editorPart instanceof ITextEditor) {
			ITextEditor editor = (ITextEditor) editorPart;
			DocumentSelection documentSelection = getDocumentSelection(editor);
			IDocument document = documentSelection.getDocument();

			if (documentSelection.hasOffset()) {
				int offset = documentSelection.getOffset();

				try {
					// Add 1 since first line in file is 0
					currentLine = document.getLineOfOffset(offset) + 1;

					//					System.out.printf("In getActivePathname: %s on line %d%n", pathname, currentLine);
				} catch (BadLocationException e) {
					currentLine = 0;
				}
			} else {
				currentLine = 0;
			}
		} else {
			currentLine = 0;
		}

		return new IndexedValue<>(currentLine, Optional.of(pathname));
	}

	public static DocumentSelection getDocumentSelection(final ITextEditor editor) {
		IDocument document = getActiveDocument(editor).get();

		// https://stackoverflow.com/a/2395953
		ISelection sel = editor.getSelectionProvider().getSelection();

		// Will be a text selection even if empty selection
		// (offset will still be what we want)
		if (sel instanceof TextSelection) {
			ITextSelection selection = (ITextSelection) sel;
			int offset = selection.getOffset();
			int length = selection.getLength();

			return new DocumentSelection(document, offset, length);
		} else {
			return new DocumentSelection(document, -1, -1);
		}
	}

	private static void setCursorOffset(final ITextEditor editor, final int offset) {
		TextSelection textSelection = new TextSelection(offset, 0);
		editor.getSelectionProvider().setSelection(textSelection);
	}

	/**
	 *
	 * @param document
	 * @param line 0 based line in the document
	 * @return
	 * @throws BadLocationException
	 */
	public static String getDocumentLine(final IDocument document, final int line) throws BadLocationException {
		int lineOffset = document.getLineOffset(line);
		int lineLength = document.getLineLength(line);
		return document.get(lineOffset, lineLength);
	}

	public static DocumentSelection getDocumentSelection() {
		Optional<ITextEditor> activeTextEditor = getActiveTextEditor();

		if (!activeTextEditor.isPresent()) {
			return new DocumentSelection(null, -1, -1);
		}

		ITextEditor editor = activeTextEditor.get();

		return getDocumentSelection(editor);
	}

	public static void insertText(final String insertText) {
		Optional<ITextEditor> activeTextEditor = getActiveTextEditor();

		if (!activeTextEditor.isPresent()) {
			return;
		}

		ITextEditor editor = activeTextEditor.get();

		DocumentSelection documentSelection = getDocumentSelection(editor);

		if (!documentSelection.hasOffset()) {
			return;
		}

		IDocument document = documentSelection.getDocument();
		int offset = documentSelection.getOffset();
		int selectionLength = documentSelection.getLength();

		// TODO: how to indicate if want line separator at end?
		// For initial release, would add line separate if no text occurs on the same line as the inserted text
		// TODO: need to move cursor after insert text
		// * If add line separator, think cursor should be on next line, after leading whitespace (like pressed enter in editor)
		// * In either case, by default, should put cursor after inserted text (as if typed by hand)
		// TODO: when allow invoking method "with parameters", the the cursor would move as the user specified the parameters
		// TODO: if insert line separator, also add leading whitespace (same as the current line)
		// TODO: special handling will be necessary for lines which start / end block with {} but can be done in later release

		try {
			String replacementText;
			if (selectionLength == 0) {
				// Inserting text, versus replacing
				int documentLine = document.getLineOfOffset(offset);
				String lineText = getDocumentLine(document, documentLine);
				IRegion region = document.getLineInformationOfOffset(offset);
				int relativeLineOffset = offset - region.getOffset();

				String afterText = lineText.substring(relativeLineOffset + selectionLength);

				boolean includeLineSeparator = afterText.trim().isEmpty();

				if (includeLineSeparator) {
					// Get leading whitespace
					String beforeText = lineText.substring(0, relativeLineOffset);

					System.out.println("Before text: '" + beforeText + "'");

					int index = 0;

					while (index < beforeText.length() && Character.isWhitespace(lineText.charAt(index))) {
						index++;
					}

					String leadingWhitespace = lineText.substring(0, index);

					System.out.println("Leading whitespace: '" + leadingWhitespace + "'");

					replacementText = insertText + System.lineSeparator() + leadingWhitespace;
				} else {
					replacementText = insertText;
				}
			} else {
				// Replacing text
				replacementText = insertText;
			}

			int newOffset = offset + replacementText.length();

			document.replace(offset, selectionLength, replacementText);
			setCursorOffset(editor, newOffset);
		} catch (BadLocationException e) {
			// Shouldn't happen, since got selection from active editor
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
