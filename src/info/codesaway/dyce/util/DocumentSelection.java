package info.codesaway.dyce.util;

import org.eclipse.jface.text.IDocument;

public final class DocumentSelection {
	private final IDocument document;
	private final int offset;
	private final int length;

	public DocumentSelection(final IDocument document, final int offset, final int length) {
		this.document = document;
		this.offset = offset;
		this.length = length;
	}

	public IDocument getDocument() {
		return this.document;
	}

	public int getOffset() {
		return this.offset;
	}

	public int getLength() {
		return this.length;
	}

	public boolean hasOffset() {
		return this.offset >= 0;
	}
}
