/*
 [The "BSD license"]
 Copyright (c) 2009 Terence Parr
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions
 are met:
 1. Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
 2. Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
 3. The name of the author may not be used to endorse or promote products
    derived from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package org.stringtemplate.v4;

import org.antlr.runtime.*;
import org.stringtemplate.v4.compiler.*;
import org.stringtemplate.v4.compiler.Compiler;
import org.stringtemplate.v4.debug.DebugST;
import org.stringtemplate.v4.misc.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

/** A directory or directory tree of .st template files and/or group files.
 *  Individual template files contain formal template definitions. In a sense,
 *  it's like a single group file broken into multiple files, one for each template.
 *  ST v3 had just the pure template inside, not the template name and header.
 *  Name inside must match filename (minus suffix).
 */
public class STGroup {
    /** When we use key as a value in a dictionary, this is how we signify. */
    public static final String DICT_KEY = "key";
    public static final String DEFAULT_KEY = "default";

    /** Load files using what encoding? */
    public String encoding = "UTF-8";

    /** Every group can import templates/dictionaries from other groups.
     *  The list must be synchronized (see importTemplates).
     */
    protected List<STGroup> imports;

    public char delimiterStartChar = '<'; // Use <expr> by default
    public char delimiterStopChar = '>';

    /** Maps template name to StringTemplate object. synchronized. */
    protected Map<String, CompiledST> templates =
		Collections.synchronizedMap(new LinkedHashMap<String, CompiledST>());

    /** Maps dict names to HashMap objects.  This is the list of dictionaries
     *  defined by the user like typeInitMap ::= ["int":"0"]
     */
    protected Map<String, Map<String,Object>> dictionaries =
        Collections.synchronizedMap(new HashMap<String, Map<String,Object>>());

    /** A dictionary that allows people to register a renderer for
     *  a particular kind of object for any template evaluated relative to this
     *  group.  For example, a date should be formatted differently depending
     *  on the locale.  You can set Date.class to an object whose
     *  toString(Object) method properly formats a Date attribute
     *  according to locale.  Or you can have a different renderer object
     *  for each locale.
     *
	 *  Order of addition is recorded and matters.  If more than one
	 *  renderer works for an object, the first registered has priority.
	 *
	 *  Renderer associated with type t works for object o if
	 *
	 * 		t.isAssignableFrom(o.getClass()) // would assignment t = o work?
	 *
	 *  So it works if o is subclass or implements t.
	 *
     *  This structure is synchronized.
     */
    protected Map<Class, AttributeRenderer> renderers;

    /** A dictionary that allows people to register a model adaptor for
     *  a particular kind of object (subclass or implementation). Applies
	 *  for any template evaluated relative to this group.
	 *
	 *  ST initializes with model adaptors that know how to pull
	 *  properties out of Objects, Maps, and STs.
	 */
	protected Map<Class, ModelAdaptor> adaptors =
		Collections.synchronizedMap(
			new LinkedHashMap<Class, ModelAdaptor>() {{
				put(Object.class, new ObjectModelAdaptor());
				put(ST.class, new STModelAdaptor());
				put(Map.class, new MapModelAdaptor());
			}}
		);

	/** Cache exact attribute type to adaptor object */
	protected Map<Class, ModelAdaptor> typeToAdaptorCache =
		Collections.synchronizedMap(new LinkedHashMap<Class, ModelAdaptor>());

	/** Cache exact attribute type to renderer object */
	protected Map<Class, AttributeRenderer> typeToRendererCache;

    /** Used to indicate that the template doesn't exist.
     *  Prevents duplicate group file loads and unnecessary file checks.
     */
    protected static final CompiledST NOT_FOUND_ST = new CompiledST();

	public static final ErrorManager DEFAULT_ERR_MGR = new ErrorManager();

	public static boolean debug = false;

	public static STGroup defaultGroup = new STGroup();

	/** The errMgr for entire group; all compilations and executions.
	 *  This gets copied to parsers, walkers, and interpreters.
	 */
	public ErrorManager errMgr = STGroup.DEFAULT_ERR_MGR;

    public STGroup() { ; }

    public STGroup(char delimiterStartChar, char delimiterStopChar) {
        this.delimiterStartChar = delimiterStartChar;
        this.delimiterStopChar = delimiterStopChar;
    }

    /** The primary means of getting an instance of a template from this
     *  group. Names must be absolute, fully-qualified names like a/b
     */
    public ST getInstanceOf(String name) {
		if ( name==null ) return null;
        //System.out.println("getInstanceOf("+name+")");
        CompiledST c = lookupTemplate(name);
        if ( c!=null ) {
            ST instanceST = createStringTemplate();
            instanceST.groupThatCreatedThisInstance = this;
            instanceST.impl = c;
			if ( instanceST.impl.formalArguments!=null ) {
				instanceST.locals = new Object[instanceST.impl.formalArguments.size()];
				Arrays.fill(instanceST.locals, ST.EMPTY_ATTR);
			}
            return instanceST;
        }
        return null;
    }

    protected ST getEmbeddedInstanceOf(ST enclosingInstance, int ip, String name) {
        ST st = getInstanceOf(name);
        if ( st==null ) {
            errMgr.runTimeError(enclosingInstance, ip, ErrorType.NO_SUCH_TEMPLATE,
                                      name);
			st = createStringTemplate();
			st.impl = new CompiledST();
			return st;
        }
        st.enclosingInstance = enclosingInstance;
        return st;
    }

	/** Create singleton template for use with dictionary values */
	public ST createSingleton(Token templateToken) {
		String template;
		if ( templateToken.getType()==GroupParser.BIGSTRING ) {
			template = Misc.strip(templateToken.getText(),2);
		}
		else {
			template = Misc.strip(templateToken.getText(),1);
		}
		ST st = createStringTemplate();
		st.groupThatCreatedThisInstance = this;
		st.impl = compile(getFileName(), null, null, template, templateToken);
		st.impl.hasFormalArgs = false;
		st.impl.name = ST.UNKNOWN_NAME;
		st.impl.defineImplicitlyDefinedTemplates(this);
		return st;
	}

    /** Is this template defined in this group or from this group below?
     *  Names must be absolute, fully-qualified names like /a/b
     */
    public boolean isDefined(String name) {
        return lookupTemplate(name)!=null;
    }

	/** Look up a fully-qualified name */
    public CompiledST lookupTemplate(String name) {
        CompiledST code = rawGetTemplate(name);
        if ( code==NOT_FOUND_ST ) return null;
        // try to load from disk and look up again
        if ( code==null ) code = load(name);
        if ( code==null ) code = lookupImportedTemplate(name);
        if ( code==null ) {
            templates.put(name, NOT_FOUND_ST);
        }
        return code;
    }

	/** "unload" all templates and dictionaries but leave renderers, adaptors,
	 *  and import relationships.  This essentially forces next getInstanceOf
	 *  to reload templates.
	 */
	public synchronized void unload() {
		templates.clear();
		dictionaries.clear();
	}

    /** Load st from disk if dir or load whole group file if .stg file (then
     *  return just one template). name is fully-qualified.
     */
    protected CompiledST load(String name) { return null; }

    /** Force a load if it makes sense for the group */
    public void load() { ; }

    protected CompiledST lookupImportedTemplate(String name) {
//        System.out.println("look for "+name+" in "+imports);
        if ( imports==null ) return null;
        for (STGroup g : imports) {
            CompiledST code = g.lookupTemplate(name);
            if ( code!=null ) return code;
        }
        return null;
    }

	public CompiledST rawGetTemplate(String name) { return templates.get(name); }
	public Map<String,Object> rawGetDictionary(String name) { return dictionaries.get(name); }
	public boolean isDictionary(String name) { return dictionaries.get(name)!=null; }

	// for testing
	public CompiledST defineTemplate(String templateName, String template) {
		try {
			CompiledST impl =
				defineTemplate(templateName,
							   new CommonToken(GroupParser.ID, templateName),
							   null, template, null);
			return impl;
		}
		catch (STException se) {
			// we have reported the error; the exception just blasts us
			// out of parsing this template
		}
		return null;
	}

	// for testing
	public CompiledST defineTemplate(String name, String argsS, String template) {
		String[] args = argsS.split(",");
		List<FormalArgument> a = new ArrayList<FormalArgument>();
		for (String arg : args) {
			a.add(new FormalArgument(arg));
		}
		return defineTemplate(name, new CommonToken(GroupParser.ID, name),
							  a, template, null);
	}

	public CompiledST defineTemplate(String templateName,
									 Token nameT,
                                     List<FormalArgument> args,
									 String template,
									 Token templateToken)
    {
		if ( templateName==null || templateName.length()==0 ) {
			throw new IllegalArgumentException("empty template name");
		}
		if ( templateName.indexOf('.')>=0 ) {
			throw new IllegalArgumentException("cannot have '.' in template names");
		}
        template = Misc.trimOneStartingNewline(template);
        template = Misc.trimOneTrailingNewline(template);
		// compile, passing in templateName as enclosing name for any embedded regions
        CompiledST code = compile(getFileName(), templateName, args, template, templateToken);
        code.name = templateName;
        rawDefineTemplate(templateName, code, nameT);
		code.defineArgDefaultValueTemplates(this);
        code.defineImplicitlyDefinedTemplates(this); // define any anonymous subtemplates

        return code;
    }

    /** Make name and alias for target.  Replace any previous def of name */
    public CompiledST defineTemplateAlias(Token aliasT, Token targetT) {
        String alias = aliasT.getText();
        String target = targetT.getText();
        CompiledST targetCode = rawGetTemplate(target);
        if ( targetCode==null ){
            errMgr.compileTimeError(ErrorType.ALIAS_TARGET_UNDEFINED, null, aliasT, alias, target);
            return null;
        }
        templates.put(alias, targetCode);
        return targetCode;
    }

    public CompiledST defineRegion(String enclosingTemplateName,
                                   Token regionT,
								   String template,
								   Token templateToken)
    {
        String name = regionT.getText();
		template = Misc.trimOneStartingNewline(template);
		template = Misc.trimOneTrailingNewline(template);
        CompiledST code = compile(getFileName(), enclosingTemplateName, null, template, regionT);
        String mangled = getMangledRegionName(enclosingTemplateName, name);

        if ( lookupTemplate(mangled)==null ) {
            errMgr.compileTimeError(ErrorType.NO_SUCH_REGION, templateToken, regionT,
                                          enclosingTemplateName, name);
            return new CompiledST();
        }
        code.name = mangled;
        code.isRegion = true;
        code.regionDefType = ST.RegionType.EXPLICIT;
		code.templateDefStartToken = regionT;

        rawDefineTemplate(mangled, code, regionT);
		code.defineArgDefaultValueTemplates(this);
		code.defineImplicitlyDefinedTemplates(this);

        return code;
    }

    public void defineTemplateOrRegion(
		String templateName,
		String regionSurroundingTemplateName,
        Token templateToken,
		String template,
        Token nameToken,
        List<FormalArgument> args)
    {
        try {
            if ( regionSurroundingTemplateName!=null ) {
                defineRegion(regionSurroundingTemplateName, nameToken, template, templateToken);
            }
            else {
                defineTemplate(templateName, nameToken, args, template, templateToken);
            }
		}
		catch (STException e) {
			// after getting syntax error in a template, we emit msg
			// and throw exception to blast all the way out here.
		}
	}

	public void rawDefineTemplate(String name, CompiledST code, Token defT) {
		CompiledST prev = rawGetTemplate(name);
		if ( prev!=null ) {
			if ( !prev.isRegion ) {
				errMgr.compileTimeError(ErrorType.TEMPLATE_REDEFINITION, null, defT);
				return;
			}
			if ( prev.isRegion ) {
				if ( code.regionDefType!=ST.RegionType.IMPLICIT &&
					 prev.regionDefType==ST.RegionType.EMBEDDED )
				{
					errMgr.compileTimeError(ErrorType.EMBEDDED_REGION_REDEFINITION,
											null,
											defT,
											getUnMangledTemplateName(name));
					return;
				}
				else if ( code.regionDefType==ST.RegionType.IMPLICIT ||
					      prev.regionDefType==ST.RegionType.EXPLICIT )
				{
					errMgr.compileTimeError(ErrorType.REGION_REDEFINITION,
											null,
											defT,
											getUnMangledTemplateName(name));
					return;
				}
			}
		}
		code.nativeGroup = this;
		code.templateDefStartToken = defT;
		templates.put(name, code);
	}

	public void undefineTemplate(String name) {
		templates.remove(name);
	}

	/** Compile a template */
	public CompiledST compile(String srcName,
							  String name,
							  List<FormalArgument> args,
							  String template,
							  Token templateToken) // for error location
    {
		//System.out.println("STGroup.compile: "+enclosingTemplateName);
		Compiler c = new Compiler(this);
		return c.compile(srcName, name, args, template, templateToken);
	}

    /** The "foo" of t() ::= "<@foo()>" is mangled to "region#t#foo" */
    public static String getMangledRegionName(String enclosingTemplateName,
                                              String name)
    {
        return "region__"+enclosingTemplateName+"__"+name;
    }

    /** Return "t.foo" from "region__t__foo" */
    public static String getUnMangledTemplateName(String mangledName) {
        String t = mangledName.substring("region__".length(),
                                         mangledName.lastIndexOf("__"));
        String r = mangledName.substring(mangledName.lastIndexOf("__")+2,
                                         mangledName.length());
        return t+'.'+r;
    }

    /** Define a map for this group; not thread safe...do not keep adding
     *  these while you reference them.
     */
    public void defineDictionary(String name, Map<String,Object> mapping) {
        dictionaries.put(name, mapping);
    }

    /** Make this group import templates/dictionaries from g. */
    public void importTemplates(STGroup g) {
        if ( g==null ) return;
        if ( imports==null ) imports = Collections.synchronizedList(new ArrayList<STGroup>());
        imports.add(g);
    }

	/** Import template files, directories, and group files.
	 *  Priority is given to templates defined in the current group;
	 *  this, in effect, provides inheritance. Polymorphism is in effect so
	 *  that if an inherited template references template t() then we
	 *  search for t() in the subgroup first.
	 *
	 *  If you specify an absolute file name or directory name, the
	 *  import statement uses that directly. If it is not an absolute path,
	 *  we look that entity up in the directory holding the group that
	 *  initiates the import. If file or directory is not in that directory,
	 *  then we load using the classpath.
	 *
	 *  Templates are loaded on-demand from import dirs.  Imported groups are
	 *  loaded on-demand when searching for a template.
	 *
	 *  The listener of this group is passed to the import group so errors
	 *  found while loading imported element are sent to listener of this group.
	 */
	public void importTemplates(Token fileNameToken) {
		String fileName = fileNameToken.getText();
		// do nothing upon syntax error
		if ( fileName==null || fileName.equals("<missing STRING>") ) return;
		fileName = Misc.strip(fileName, 1);

		//System.out.println("import "+fileName);
		boolean isGroupFile = fileName.endsWith(".stg");
		boolean isTemplateFile = fileName.endsWith(".st");
		boolean isGroupDir = !(isGroupFile || isTemplateFile);

		STGroup g = null;

		File f = new File(fileName);
		if ( f.isAbsolute() ) { // load directly if absolute
			if ( isTemplateFile ) {
				g = new STGroup();
				g.setListener(this.getListener());
				g.loadAbsoluteTemplateFile(fileName);
			}
			else if ( isGroupFile ) {
				g = new STGroupFile(fileName, delimiterStartChar, delimiterStopChar);
				g.setListener(this.getListener());
			}
			else if ( isGroupDir ) {
				g = new STGroupDir(fileName, delimiterStartChar, delimiterStopChar);
				g.setListener(this.getListener());
			}
			importTemplates(g);
			return;
		}

		// it's a relative name; search path is working dir, g.stg's dir, CLASSPATH
		String thisRoot = getRootDir();
		String fileUnderRoot = null;
		//System.out.println("thisRoot="+thisRoot);
		fileUnderRoot = thisRoot+"/"+fileName;
		if ( isTemplateFile ) {
			g = new STGroup();
			g.setListener(this.getListener());
			InputStream s = null;
			try {
				URL url = new File(fileUnderRoot).toURI().toURL();
				s = url.openStream();
				ANTLRInputStream templateStream = new ANTLRInputStream(s);
				templateStream.name = fileName;
				CompiledST code = g.loadTemplateFile("", fileName, templateStream);
				if ( code==null ) g = null;
			}
			catch (MalformedURLException mfe) {
				errMgr.internalError(null, "can't build URL for "+fileUnderRoot, mfe);
			}
			catch (IOException ioe) {
				// not found
				g = null;
			}
		}
		else if ( isGroupFile ) {
			try {
				//System.out.println("look for fileUnderRoot: "+fileUnderRoot);
				g = new STGroupFile(fileUnderRoot, encoding, delimiterStartChar, delimiterStopChar);
				g.setListener(this.getListener());
			}
			catch (IllegalArgumentException iae) { // not relative to this group
				//System.out.println("look in path: "+fileName);
				// try in CLASSPATH
				try {
					g = new STGroupFile(fileName, delimiterStartChar, delimiterStopChar);
					g.setListener(this.getListener());
				}
				catch (IllegalArgumentException iae2) {
					g = null;
				}
			}
		}
		else if ( isGroupDir ) {
			try {
				g = new STGroupDir(fileUnderRoot, encoding, delimiterStartChar, delimiterStopChar);
				g.setListener(this.getListener());
			}
			catch (IllegalArgumentException iae) { // not relative to this group
				// try in CLASSPATH
				try {
					g = new STGroupDir(fileName, delimiterStartChar, delimiterStopChar);
					g.setListener(this.getListener());
				}
				catch (IllegalArgumentException iae2) {
					g = null;
				}
			}
		}

		if ( g==null ) {
			errMgr.compileTimeError(ErrorType.CANT_IMPORT, null,
									fileNameToken, fileName);
		}
		else {
			importTemplates(g);
		}
	}

	/** Load a group file with full path fileName; it's relative to root by prefix. */
	public void loadGroupFile(String prefix, String fileName) {
		//System.out.println("load group file prefix="+prefix+", fileName="+fileName);
		GroupParser parser = null;
		try {
			URL f = new URL(fileName);
			ANTLRInputStream fs = new ANTLRInputStream(f.openStream(), encoding);
			GroupLexer lexer = new GroupLexer(fs);
			fs.name = fileName;
			CommonTokenStream tokens = new CommonTokenStream(lexer);
			parser = new GroupParser(tokens);
			parser.group(this, prefix);
		}
		catch (Exception e) {
			errMgr.IOError(null, ErrorType.CANT_LOAD_GROUP_FILE, e, fileName);
		}
	}

	/** Load template file into this group using absolute filename */
	public CompiledST loadAbsoluteTemplateFile(String fileName) {
		ANTLRFileStream fs;
		try {
			fs = new ANTLRFileStream(fileName, encoding);
			fs.name = fileName;
		}
		catch (IOException ioe) {
			// doesn't exist
			//errMgr.IOError(null, ErrorType.NO_SUCH_TEMPLATE, ioe, fileName);
			return null;
		}
		return loadTemplateFile("", fileName, fs);
	}

	/** Load template stream into this group */
	public CompiledST loadTemplateFile(String prefix, String fileName, CharStream templateStream) {
		GroupLexer lexer = new GroupLexer(templateStream);
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		GroupParser parser = new GroupParser(tokens);
		parser.group = this;
		lexer.group = this;
		try {
			parser.templateDef(prefix);
		}
		catch (RecognitionException re) {
			errMgr.groupSyntaxError(ErrorType.SYNTAX_ERROR,
									fileName,
									re, re.getMessage());
		}
		String templateName = Misc.getFileNameNoSuffix(fileName);
		if ( prefix!=null && prefix.length()>0 ) templateName = prefix+"/"+templateName;
		return rawGetTemplate(templateName);
	}

	/** Add an adaptor for a kind of object so ST knows how to pull properties
	 *  from them. Add adaptors in increasing order of specificity.  ST adds Object,
	 *  Map, and ST model adaptors for you first. Adaptors you add have
	 *  priority over default adaptors.
	 *
	 *  If an adaptor for type T already exists, it is replaced by the adaptor arg.
	 *
	 *  This must invalidate cache entries, so set your adaptors up before
	 *  render()ing your templates for efficiency.
	 */
	public void registerModelAdaptor(Class attributeType, ModelAdaptor adaptor) {
		if ( attributeType.isPrimitive() ) {
			throw new IllegalArgumentException("can't register ModelAdaptor for primitive type "+
											   attributeType.getSimpleName());
		}
		adaptors.put(attributeType, adaptor);
		invalidateModelAdaptorCache(attributeType);
	}

	/** remove at least all types in cache that are subclasses or implement attributeType */
	public void invalidateModelAdaptorCache(Class attributeType) {
		typeToAdaptorCache.clear(); // be safe, not clever; wack all values
	}

	public ModelAdaptor getModelAdaptor(Class attributeType) {
		ModelAdaptor a = typeToAdaptorCache.get(attributeType);
		if ( a!=null ) return a;

		// Else, we must find adaptor that fits;
		// find last fit (most specific)
		for (Class t : adaptors.keySet()) {
			// t works for attributeType if attributeType subclasses t or implements
			if ( t.isAssignableFrom(attributeType) ) {
				//System.out.println(t.getName()+" = "+attributeType.getName());
				a = adaptors.get(t);
			}
		}
		typeToAdaptorCache.put(attributeType, a); // cache it for next time
		return a;
	}

    /** Register a renderer for all objects of a particular "kind" for all
     *  templates evaluated relative to this group.  Use r to render if
	 *  object in question is instanceof(attributeType).
     */
    public void registerRenderer(Class attributeType, AttributeRenderer r) {
		if ( attributeType.isPrimitive() ) {
			throw new IllegalArgumentException("can't register renderer for primitive type "+
											   attributeType.getSimpleName());
		}
		typeToAdaptorCache.clear(); // be safe, not clever; wack all values
        if ( renderers ==null ) {
            renderers =
				Collections.synchronizedMap(new LinkedHashMap<Class, AttributeRenderer>());
        }
        renderers.put(attributeType, r);
	}

	public AttributeRenderer getAttributeRenderer(Class attributeType) {
		if ( renderers==null ) return null;
		AttributeRenderer r = null;
		if ( typeToRendererCache!=null ) {
			r = typeToRendererCache.get(attributeType);
			if ( r!=null ) return r;
		}

		// Else look up, finding first first
		for (Class t : renderers.keySet()) {
			// t works for attributeType if attributeType subclasses t or implements
			if ( t.isAssignableFrom(attributeType) ) {
				r = renderers.get(t);
				if ( typeToRendererCache==null ) {
					typeToRendererCache =
						Collections.synchronizedMap(new LinkedHashMap<Class, AttributeRenderer>());
				}
				typeToRendererCache.put(attributeType, r);
				return r;
			}
		}
		return null;
	}

    /** StringTemplate object factory; each group can have its own. */
	public ST createStringTemplate() {
		// TODO: try making a mem pool?
		if ( debug ) {
			return new DebugST();
		}
		return new ST();
	}

	public ST createStringTemplate(ST proto) {
		if ( debug ) {
			return new DebugST(proto);
		}
		return new ST(proto);
	}

    public String getName() { return "<no name>;"; }
	public String getFileName() { return null; }

	/** Return root dir if this is group dir; return dir containing group file
	 *  if this is group file.  This is derived from original incoming
	 *  dir or filename.  If it was absolute, this should come back
	 *  as full absolute path.  If it was org/foo/templates then this should
	 *  be org/foo/templates.  org/foo/templates/main.stg ->
	 *  org/foo/templates.
	 */
	public String getRootDir() { return null; }

    public String toString() { return getName(); }

    public String show() {
        StringBuilder buf = new StringBuilder();
        if ( imports!=null ) buf.append(" : "+imports);
        for (String name : templates.keySet()) {
			CompiledST c = rawGetTemplate(name);
			if ( c.isAnonSubtemplate || c==NOT_FOUND_ST ) continue;
            int slash = name.lastIndexOf('/');
            name = name.substring(slash+1, name.length());
            buf.append(name);
            buf.append('(');
            if ( c.formalArguments!=null ) buf.append( Misc.join(c.formalArguments.values().iterator(), ",") );
            buf.append(')');
            buf.append(" ::= <<"+Misc.newline);
            buf.append(c.template+ Misc.newline);
            buf.append(">>"+Misc.newline);
        }
        return buf.toString();
    }

	public STErrorListener getListener() {
		return errMgr.listener;
	}

	public void setListener(STErrorListener listener) {
		errMgr = new ErrorManager(listener);
	}
}
