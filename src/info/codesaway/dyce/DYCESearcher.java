package info.codesaway.dyce;

import java.nio.file.Path;

public class DYCESearcher {
	private final String name;
	private final Path indexPath;
	private final int hitLimit;

	public DYCESearcher(final String name, final Path indexPath, final int hitLimit) {
		this.name = name;
		this.indexPath = indexPath;
		this.hitLimit = hitLimit;
	}

	public String getName() {
		return this.name;
	}

	public Path getIndexPath() {
		return this.indexPath;
	}

	public int getHitLimit() {
		return this.hitLimit;
	}
}
