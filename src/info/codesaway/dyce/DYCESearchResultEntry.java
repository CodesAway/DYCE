package info.codesaway.dyce;

public class DYCESearchResultEntry {
	private final int resultIndex;
	private final String resultNumber;
	private final String packageName;
	private final String className;
	private final String file;
	private final String element;
	private final String line;
	//	private final String content;
	private final String type;
	private final String path;
	//	private final String extension;

	/**
	 *
	 * @param resultIndex the result index (starts with 1)
	 * @param file
	 * @param line
	 * @param content
	 * @param type
	 * @param path
	 */
	public DYCESearchResultEntry(final int resultIndex, final String packageName, final String className,
			final String file, final String element, final String line,
			final String type, final String path) {
		this.resultIndex = resultIndex;
		this.resultNumber = String.valueOf(resultIndex);

		this.packageName = packageName;
		this.className = className;
		this.file = file;
		this.element = element;
		this.line = line;
		this.type = type;
		this.path = path;
	}

	public int getResultIndex() {
		return this.resultIndex;
	}

	public String getResultNumber() {
		return this.resultNumber;
	}

	public String getPackageName() {
		return this.packageName;
	}

	public String getClassName() {
		return this.className;
	}

	public String getFile() {
		return this.file;
	}

	public String getElement() {
		return this.element;
	}

	public String getLine() {
		return this.line;
	}

	public String getType() {
		return this.type;
	}

	public String getPath() {
		return this.path;
	}

	@Override
	public String toString() {
		return this.getFile() + ":" + this.getLine();
	}

	public int getLineNumber() {
		try {
			return Integer.parseInt(this.getLine());
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
