/**
 * @author Feng Chen, Dongyun Jin
 * The class handling the mop specification tree
 */

package com.runtimeverification.rvmonitor.java.rvj;

import com.runtimeverification.rvmonitor.java.rvj.logicclient.LogicRepositoryConnector;
import com.runtimeverification.rvmonitor.java.rvj.logicpluginshells.LogicPluginShellFactory;
import com.runtimeverification.rvmonitor.java.rvj.logicpluginshells.LogicPluginShellResult;
import com.runtimeverification.rvmonitor.java.rvj.output.AspectJCode;
import com.runtimeverification.rvmonitor.java.rvj.output.JavaLibCode;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.RVMSpecFile;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.body.BodyDeclaration;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.mopspec.EventDefinition;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.mopspec.RVMParameter;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.mopspec.RVMonitorSpec;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.mopspec.PropertyAndHandlers;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.visitor.CollectUserVarVisitor;
import com.runtimeverification.rvmonitor.java.rvj.parser.logicrepositorysyntax.LogicRepositoryType;
import com.runtimeverification.rvmonitor.java.rvj.util.Tool;

import java.util.List;

public class RVMProcessor {
	public static boolean verbose = false;

	public String name;

	public RVMProcessor(String name) {
		this.name = name;
	}

	private void registerUserVar(RVMonitorSpec mopSpec) throws RVMException {
		for (EventDefinition event : mopSpec.getEvents()) {
			RVMNameSpace.addUserVariable(event.getId());
			for(RVMParameter param : event.getRVMParameters()){
				RVMNameSpace.addUserVariable(param.getName());
			}
		}
		for (RVMParameter param : mopSpec.getParameters()) {
			RVMNameSpace.addUserVariable(param.getName());
		}
		RVMNameSpace.addUserVariable(mopSpec.getName());
		for (BodyDeclaration bd : mopSpec.getDeclarations()) {
			List<String> vars = bd.accept(new CollectUserVarVisitor(), null);

			if (vars != null)
				RVMNameSpace.addUserVariables(vars);
		}
	}

	public String process(RVMSpecFile rvmSpecFile) throws RVMException {
		String result;

		// register all user variables to RVMNameSpace to avoid conflicts
		for(RVMonitorSpec mopSpec : rvmSpecFile.getSpecs())
			registerUserVar(mopSpec);

		// Connect to Logic Repository
		for(RVMonitorSpec mopSpec : rvmSpecFile.getSpecs()){
			for (PropertyAndHandlers prop : mopSpec.getPropertiesAndHandlers()) {
				// connect to the logic repository and get the logic output
				LogicRepositoryType logicOutput = LogicRepositoryConnector.process(mopSpec, prop);
				// get the monitor from the logic shell
				LogicPluginShellResult logicShellOutput = LogicPluginShellFactory.process(logicOutput, mopSpec.getEventStr());
				prop.setLogicShellOutput(logicShellOutput);
				
				if(logicOutput.getMessage().contains("versioned stack")){
					prop.setVersionedStack();
				}

				if (verbose) {
					System.out.println("== result from logic shell ==");
					System.out.print(logicShellOutput);
					System.out.println("");
				}
			}
		}

		// Error Checker
		for(RVMonitorSpec mopSpec : rvmSpecFile.getSpecs()){
			RVMErrorChecker.verify(mopSpec);
		}

		// Generate output code
		if (Main.toJavaLib)
			result = (new JavaLibCode(name, rvmSpecFile)).toString();
		else
			result = (new AspectJCode(name, rvmSpecFile)).toString();


		// Do indentation
		result = Tool.changeIndentation(result, "", "\t");

		return result;
	}


}