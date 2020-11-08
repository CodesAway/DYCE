package info.codesaway.dyce;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * The activator class controls the plug-in life cycle
 */
public class Activator extends AbstractUIPlugin {

	// The plug-in ID
	public static final String PLUGIN_ID = "info.codesaway.dyce"; //$NON-NLS-1$

	// The shared instance
	private static Activator INSTANCE;

	public static Path STATE_LOCATION;

	public static final IWorkspace WORKSPACE = ResourcesPlugin.getWorkspace();
	public static final IWorkspaceRoot WORKSPACE_ROOT = WORKSPACE.getRoot();
	public static final String WORKSPACE_PATHNAME = WORKSPACE_ROOT.getLocation().toString();
	public static final Path WORKSPACE_PATH = Paths.get(WORKSPACE_PATHNAME);

	@Override
	public void start(final BundleContext context) throws Exception {
		super.start(context);
		INSTANCE = this;

		STATE_LOCATION = this.getStateLocation().toFile().toPath();
	}

	@Override
	public void stop(final BundleContext context) throws Exception {
		INSTANCE = null;
		super.stop(context);

		DYCEView.cancelJobs();
	}

	/**
	 * Returns the shared instance
	 *
	 * @return the shared instance
	 */
	public static Activator getDefault() {
		return INSTANCE;
	}
}
