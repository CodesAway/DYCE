package info.codesaway.dyce.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;

import info.codesaway.dyce.indexer.DYCEIndexer;

public class RebuildIndexHandler extends AbstractHandler {

	@Override
	public Object execute(final ExecutionEvent event) throws ExecutionException {
		DYCEIndexer.rebuildEntireIndex();
		return null;
	}
}
