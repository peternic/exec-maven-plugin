package org.codehaus.mojo.exec;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.artifact.MavenMetadataSource;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Executes the supplied java class in the current VM with the enclosing project's
 * dependencies as classpath.
 *
 * @author <a href="mailto:kaare.nilsen@gmail.com">Kaare Nilsen</a>, <a href="mailto:dsmiley@mitre.org">David Smiley</a>
 * @goal java
 * @requiresDependencyResolution test
 * @execute phase="validate"
 * @since 1.0
 */
public class ExecJavaMojo
    extends AbstractExecMojo
{
    /**
     * @component
     */
    private ArtifactResolver artifactResolver;

    /**
     * @component
     */
    private ArtifactFactory artifactFactory;

    /**
     * @component
     */
    private ArtifactMetadataSource metadataSource;

    /**
     * @parameter default-value="${localRepository}"
     * @required
     * @readonly
     * @since 1.0
     */
    private ArtifactRepository localRepository;

    /**
     * @parameter default-value="${project.remoteArtifactRepositories}"
     * @required
     * @readonly
     * @since 1.1-beta-1
     */
    private List remoteRepositories;

    /**
     * @component
     * @since 1.0
     */
    private MavenProjectBuilder projectBuilder;

    /**
     * @parameter default-value="${plugin.artifacts}"
     * @readonly
     * @since 1.1-beta-1
     */
    private List pluginDependencies;

    /**
     * The main class to execute.
     *
     * @parameter expression="${exec.mainClass}"
     * @required
     * @since 1.0
     */
    private String mainClass;

    /**
     * The class arguments.
     *
     * @parameter expression="${exec.arguments}"
     * @since 1.0
     */
    private String[] arguments;

    /**
     * A list of system properties to be passed. Note: as the execution is not forked, some system properties
     * required by the JVM cannot be passed here. Use MAVEN_OPTS or the exec:exec instead. See the user guide for
     * more information.
     *
     * @parameter
     * @since 1.0
     */
    private Property[] systemProperties;

    /**
     * Indicates if mojo should be kept running after the mainclass terminates.
     * Useful for server-like apps with deamonthreads.
     *
     * @parameter expression="${exec.keepAlive}" default-value="false"
     * @deprecated since 1.1-alpha-1
     * @since 1.0
     */
    private boolean keepAlive;

    /**
     * Indicates if the project dependencies should be used when executing
     * the main class.
     *
     * @parameter expression="${exec.includeProjectDependencies}" default-value="true"
     * @since 1.1-beta-1
     */
    private boolean includeProjectDependencies;

    /**
     * Indicates if this plugin's dependencies should be used when executing
     * the main class.
     * <p/>
     * This is useful when project dependencies are not appropriate.  Using only
     * the plugin dependencies can be particularly useful when the project is
     * not a java project.  For example a mvn project using the csharp plugins
     * only expects to see dotnet libraries as dependencies.
     *
     * @parameter expression="${exec.includePluginDependencies}" default-value="false"
     * @since 1.1-beta-1
     */
    private boolean includePluginDependencies;

    /**
     * If provided the ExecutableDependency identifies which of the plugin dependencies
     * contains the executable class.  This will have the affect of only including
     * plugin dependencies required by the identified ExecutableDependency.
     * <p/>
     * If includeProjectDependencies is set to <code>true</code>, all of the project dependencies
     * will be included on the executable's classpath.  Whether a particular project
     * dependency is a dependency of the identified ExecutableDependency will be
     * irrelevant to its inclusion in the classpath.
     *
     * @parameter
     * @optional
     * @since 1.1-beta-1
     */
    private ExecutableDependency executableDependency;

    /**
     * Whether to interrupt/join and possibly stop the daemon threads upon quitting. <br/> If this is <code>false</code>,
     *  maven does nothing about the daemon threads.  When maven has no more work to do, the VM will normally terminate
     *  any remaining daemon threads.
     * <p>
     * In certain cases (in particular if maven is embedded),
     *  you might need to keep this enabled to make sure threads are properly cleaned up to ensure they don't interfere
     * with subsequent activity.
     * In that case, see {@link #daemonThreadJoinTimeout} and
     * {@link #stopUnresponsiveDaemonThreads} for further tuning.
     * </p>
     * @parameter expression="${exec.cleanupDaemonThreads} default-value="true"
     * @since 1.1-beta-1
     */
     private boolean cleanupDaemonThreads;

     /**
     * This defines the number of milliseconds to wait for daemon threads to quit following their interruption.<br/>
     * This is only taken into account if {@link #cleanupDaemonThreads} is <code>true</code>.
     * A value &lt;=0 means to not timeout (i.e. wait indefinitely for threads to finish). Following a timeout, a
     * warning will be logged.
     * <p>Note: properly coded threads <i>should</i> terminate upon interruption but some threads may prove
     * problematic:  as the VM does interrupt daemon threads, some code may not have been written to handle
     * interruption properly. For example java.util.Timer is known to not handle interruptions in JDK &lt;= 1.6.
     * So it is not possible for us to infinitely wait by default otherwise maven could hang. A  sensible default 
     * value has been chosen, but this default value <i>may change</i> in the future based on user feedback.</p>
     * @parameter expression="${exec.daemonThreadJoinTimeout}" default-value="15000"
     * @since 1.1-beta-1
     */
    private long daemonThreadJoinTimeout;

    /**
     * Whether to call {@link Thread#stop()} following a timing out of waiting for an interrupted thread to finish.
     * This is only taken into account if {@link #cleanupDaemonThreads} is <code>true</code>
     * and the {@link #daemonThreadJoinTimeout} threshold has been reached for an uncooperative thread.
     * If this is <code>false</code>, or if {@link Thread#stop()} fails to get the thread to stop, then
     * a warning is logged and Maven will continue on while the affected threads (and related objects in memory)
     * linger on.  Consider setting this to <code>true</code> if you are invoking problematic code that you can't fix. 
     * An example is {@link java.util.Timer} which doesn't respond to interruption.  To have <code>Timer</code>
     * fixed, vote for <a href="http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6336543">this bug</a>.
     * @parameter expression="${exec.stopUnresponsiveDaemonThreads} default-value="false"
     * @since 1.1-beta-1
     */
    private boolean stopUnresponsiveDaemonThreads;

    /**
     * Deprecated this is not needed anymore.
     *
     * @parameter expression="${exec.killAfter}" default-value="-1"
     * @deprecated since 1.1-alpha-1
     * @since 1.0
     */
    private long killAfter;
        
    private Properties originalSystemProperties;

    /**
     * Execute goal.
     * @throws MojoExecutionException execution of the main class or one of the threads it generated failed.
     * @throws MojoFailureException something bad happened...
     */
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        if ( isSkip() )
        {
            getLog().info( "skipping execute as per configuration" );
            return;
        }
        if ( killAfter != -1 )
        {
            getLog().warn( "Warning: killAfter is now deprecated. Do you need it ? Please comment on MEXEC-6." );
        }

        if ( null == arguments )
        {
            arguments = new String[0];
        }

        if ( getLog().isDebugEnabled() )
        {
            StringBuffer msg = new StringBuffer( "Invoking : " );
            msg.append( mainClass );
            msg.append( ".main(" );
            for ( int i = 0; i < arguments.length; i++ )
            {
                if ( i > 0 )
                {
                    msg.append( ", " );
                }
                msg.append( arguments[i] );
            }
            msg.append( ")" );
            getLog().debug(  msg );
        }

        IsolatedThreadGroup threadGroup = new IsolatedThreadGroup( mainClass /*name*/ );
        Thread bootstrapThread = new Thread( threadGroup, new Runnable()
        {
            public void run()
            {
                try
                {
                    Method main = Thread.currentThread().getContextClassLoader().loadClass( mainClass )
                        .getMethod( "main", new Class[]{ String[].class } );
                    if ( !main.isAccessible() )
                    {
                        getLog().debug( "Setting accessibility to true in order to invoke main()." );
                        main.setAccessible( true );
                    }
                    if ( !Modifier.isStatic( main.getModifiers() ) )
                    {
                        throw new MojoExecutionException( 
                                 "Can't call main(String[])-method because it is not static." );
                    }
                    main.invoke( null, new Object[] { arguments } );
                }
                catch ( NoSuchMethodException e )
                {   // just pass it on
                    Thread.currentThread().getThreadGroup().uncaughtException( Thread.currentThread(), 
                          new Exception( 
                               "The specified mainClass doesn't contain a main method with appropriate signature.", e
                          )
                       );
                }
                catch ( Exception e )
                {   // just pass it on
                    Thread.currentThread().getThreadGroup().uncaughtException( Thread.currentThread(), e );
                }
            }
        }, mainClass + ".main()" );
        bootstrapThread.setContextClassLoader( getClassLoader() );
        setSystemProperties();

        bootstrapThread.start();
        joinNonDaemonThreads( threadGroup );
        // It's plausible that spontaneously a non-daemon thread might be created as we try and shut down,
        // but it's too late since the termination condition (only daemon threads) has been triggered.
        if ( keepAlive )
        {
            getLog().warn(
                "Warning: keepAlive is now deprecated and obsolete. Do you need it? Please comment on MEXEC-6." );
            waitFor( 0 );
        }

        if ( cleanupDaemonThreads )
        {
        
            terminateThreads( threadGroup );
            
            try
            {
                threadGroup.destroy();
            }
            catch ( IllegalThreadStateException e )
            {
                getLog().warn( "Couldn't destroy threadgroup " + threadGroup, e );
            }
        }
        

        if ( originalSystemProperties != null )
        {
            System.setProperties( originalSystemProperties );
        }

        synchronized ( threadGroup )
        {
            if ( threadGroup.uncaughtException != null )
            {
                throw new MojoExecutionException( "An exception occurred while executing the Java class. "
                                                  + threadGroup.uncaughtException.getMessage(),
                                                  threadGroup.uncaughtException );
            }
        }

        registerSourceRoots();
    }

    /**
     * a ThreadGroup to isolate execution and collect exceptions.
     */
    class IsolatedThreadGroup extends ThreadGroup
    {
        private Throwable uncaughtException; // synchronize access to this

        public IsolatedThreadGroup( String name )
        {
            super( name );
        }

        public void uncaughtException( Thread thread, Throwable throwable )
        {
            if ( throwable instanceof ThreadDeath )
            {
                return; //harmless
            }
            synchronized ( this )
            {
                if ( uncaughtException == null ) // only remember the first one
                {
                    uncaughtException = throwable; // will be reported eventually
                }
            }
            getLog().warn( throwable );
        }
    }

    private void joinNonDaemonThreads( ThreadGroup threadGroup )
    {
        boolean foundNonDaemon;
        do
        {
            foundNonDaemon = false;
            Collection threads = getActiveThreads( threadGroup );
            for ( Iterator iter = threads.iterator(); iter.hasNext(); )
            {
                Thread thread = (Thread) iter.next();
                if ( thread.isDaemon() )
                {
                    continue;
                }
                foundNonDaemon = true;   //try again; maybe more threads were created while we were busy
                joinThread( thread, 0 );
            }
        } while ( foundNonDaemon );
    }

    private void joinThread( Thread thread, long timeoutMsecs )
    {
        try
        {
            getLog().debug( "joining on thread " + thread );
            thread.join( timeoutMsecs );
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();   // good practice if don't throw
            getLog().warn( "interrupted while joining against thread " + thread, e );   // not expected!
        }
        if ( thread.isAlive() ) //generally abnormal
        {
            getLog().warn( "thread " + thread + " was interrupted but is still alive after waiting at least "
                + timeoutMsecs + "msecs" );
        }
    }

    private void terminateThreads( ThreadGroup threadGroup )
    {
        long startTime = System.currentTimeMillis();
        Set uncooperativeThreads = new HashSet(); // these were not responsive to interruption
        for ( Collection threads = getActiveThreads( threadGroup ); !threads.isEmpty();
              threads = getActiveThreads( threadGroup ), threads.removeAll( uncooperativeThreads ) )
        {
            // Interrupt all threads we know about as of this instant (harmless if spuriously went dead (! isAlive())
            //   or if something else interrupted it ( isInterrupted() ).
            for ( Iterator iter = threads.iterator(); iter.hasNext(); )
            {
                Thread thread = (Thread) iter.next();
                getLog().debug( "interrupting thread " + thread );
                thread.interrupt();
            }
            // Now join with a timeout and call stop() (assuming flags are set right)
            for ( Iterator iter = threads.iterator(); iter.hasNext(); )
            {
                Thread thread = (Thread) iter.next();
                if ( ! thread.isAlive() )
                {
                    continue; //and, presumably it won't show up in getActiveThreads() next iteration
                }
                if ( daemonThreadJoinTimeout <= 0 )
                {
                    joinThread( thread, 0 ); //waits until not alive; no timeout
                    continue;
                }
                long timeout = daemonThreadJoinTimeout 
                               - ( System.currentTimeMillis() - startTime );
                if ( timeout > 0 )
                {
                    joinThread( thread, timeout );
                }
                if ( ! thread.isAlive() )
                {
                    continue;
                }
                uncooperativeThreads.add( thread ); // ensure we don't process again
                if ( stopUnresponsiveDaemonThreads )
                {
                    getLog().warn( "thread " + thread + " will be Thread.stop()'ed" );
                    thread.stop();
                }
                else
                {
                    getLog().warn( "thread " + thread + " will linger despite being asked to die via interruption" );
                }
            }
        }
        if ( ! uncooperativeThreads.isEmpty() )
        {
            getLog().warn( "NOTE: " + uncooperativeThreads.size() + " thread(s) did not finish despite being asked to "
                + " via interruption. This is not a problem with exec:java, it is a problem with the running code."
                + " Although not serious, it should be remedied." );
        }
        else
        {
            int activeCount = threadGroup.activeCount();
            if ( activeCount != 0 )
            {
                // TODO this may be nothing; continue on anyway; perhaps don't even log in future
                Thread[] threadsArray = new Thread[1];
                threadGroup.enumerate( threadsArray );
                getLog().debug( "strange; " + activeCount
                        + " thread(s) still active in the group " + threadGroup + " such as " + threadsArray[0] );
            }
        }
    }

    private Collection getActiveThreads( ThreadGroup threadGroup )
    {
        Thread[] threads = new Thread[ threadGroup.activeCount() ];
        int numThreads = threadGroup.enumerate( threads );
        Collection result = new ArrayList( numThreads );
        for ( int i = 0; i < threads.length && threads[i] != null; i++ )
        {
            result.add( threads[i] );
        }
        return result; //note: result should be modifiable
    }

    /**
     * Pass any given system properties to the java system properties.
     */
    private void setSystemProperties()
    {
        if ( systemProperties != null )
        {
            originalSystemProperties = System.getProperties();
            for ( int i = 0; i < systemProperties.length; i++ )
            {
                Property systemProperty = systemProperties[i];
                String value = systemProperty.getValue();
                System.setProperty( systemProperty.getKey(), value == null ? "" : value );
            }
        }
    }

    /**
     * Set up a classloader for the execution of the main class.
     *
     * @return the classloader
     * @throws MojoExecutionException if a problem happens
     */
    private ClassLoader getClassLoader()
        throws MojoExecutionException
    {
        List classpathURLs = new ArrayList();
        this.addRelevantPluginDependenciesToClasspath( classpathURLs );
        this.addRelevantProjectDependenciesToClasspath( classpathURLs );
        return new URLClassLoader( ( URL[] ) classpathURLs.toArray( new URL[ classpathURLs.size() ] ) );
    }

    /**
     * Add any relevant project dependencies to the classpath.
     * Indirectly takes includePluginDependencies and ExecutableDependency into consideration.
     *
     * @param path classpath of {@link java.net.URL} objects
     * @throws MojoExecutionException if a problem happens
     */
    private void addRelevantPluginDependenciesToClasspath( List path )
        throws MojoExecutionException
    {
        if ( hasCommandlineArgs() )
        {
            arguments = parseCommandlineArgs();
        }

        try
        {
            Iterator iter = this.determineRelevantPluginDependencies().iterator();
            while ( iter.hasNext() )
            {
                Artifact classPathElement = (Artifact) iter.next();
                getLog().debug(
                    "Adding plugin dependency artifact: " + classPathElement.getArtifactId() + " to classpath" );
                path.add( classPathElement.getFile().toURI().toURL() );
            }
        }
        catch ( MalformedURLException e )
        {
            throw new MojoExecutionException( "Error during setting up classpath", e );
        }

    }

    /**
     * Add any relevant project dependencies to the classpath.
     * Takes includeProjectDependencies into consideration.
     *
     * @param path classpath of {@link java.net.URL} objects
     * @throws MojoExecutionException if a problem happens
     */
    private void addRelevantProjectDependenciesToClasspath( List path )
        throws MojoExecutionException
    {
        if ( this.includeProjectDependencies )
        {
            try
            {
                getLog().debug( "Project Dependencies will be included." );

                List artifacts = new ArrayList();
                List theClasspathFiles = new ArrayList();
 
                collectProjectArtifactsAndClasspath( artifacts, theClasspathFiles );

                for ( Iterator it = theClasspathFiles.iterator(); it.hasNext(); )
                {
                     URL url = ( (File) it.next() ).toURI().toURL();
                     getLog().debug( "Adding to classpath : " + url );
                     path.add( url );
                }

                Iterator iter = artifacts.iterator();
                while ( iter.hasNext() )
                {
                    Artifact classPathElement = (Artifact) iter.next();
                    getLog().debug(
                        "Adding project dependency artifact: " + classPathElement.getArtifactId() + " to classpath" );
                    path.add( classPathElement.getFile().toURI().toURL() );
                }

            }
            catch ( MalformedURLException e )
            {
                throw new MojoExecutionException( "Error during setting up classpath", e );
            }
        }
        else
        {
            getLog().debug( "Project Dependencies will be excluded." );
        }

    }

    /**
     * Determine all plugin dependencies relevant to the executable.
     * Takes includePlugins, and the executableDependency into consideration.
     *
     * @return a set of Artifact objects.
     *         (Empty set is returned if there are no relevant plugin dependencies.)
     * @throws MojoExecutionException if a problem happens resolving the plugin dependencies
     */
    private Set determineRelevantPluginDependencies()
        throws MojoExecutionException
    {
        Set relevantDependencies;
        if ( this.includePluginDependencies )
        {
            if ( this.executableDependency == null )
            {
                getLog().debug( "All Plugin Dependencies will be included." );
                relevantDependencies = new HashSet( this.pluginDependencies );
            }
            else
            {
                getLog().debug( "Selected plugin Dependencies will be included." );
                Artifact executableArtifact = this.findExecutableArtifact();
                Artifact executablePomArtifact = this.getExecutablePomArtifact( executableArtifact );
                relevantDependencies = this.resolveExecutableDependencies( executablePomArtifact );
            }
        }
        else
        {
            relevantDependencies = Collections.EMPTY_SET;
            getLog().debug( "Plugin Dependencies will be excluded." );
        }
        return relevantDependencies;
    }

    /**
     * Get the artifact which refers to the POM of the executable artifact.
     *
     * @param executableArtifact this artifact refers to the actual assembly.
     * @return an artifact which refers to the POM of the executable artifact.
     */
    private Artifact getExecutablePomArtifact( Artifact executableArtifact )
    {
        return this.artifactFactory.createBuildArtifact( executableArtifact.getGroupId(),
                                                         executableArtifact.getArtifactId(),
                                                         executableArtifact.getVersion(), "pom" );
    }

    /**
     * Examine the plugin dependencies to find the executable artifact.
     *
     * @return an artifact which refers to the actual executable tool (not a POM)
     * @throws MojoExecutionException if no executable artifact was found
     */
    private Artifact findExecutableArtifact()
        throws MojoExecutionException
    {
        //ILimitedArtifactIdentifier execToolAssembly = this.getExecutableToolAssembly();

        Artifact executableTool = null;
        for ( Iterator iter = this.pluginDependencies.iterator(); iter.hasNext(); )
        {
            Artifact pluginDep = (Artifact) iter.next();
            if ( this.executableDependency.matches( pluginDep ) )
            {
                executableTool = pluginDep;
                break;
            }
        }

        if ( executableTool == null )
        {
            throw new MojoExecutionException(
                "No dependency of the plugin matches the specified executableDependency."
                + "  Specified executableToolAssembly is: " + executableDependency.toString() );
        }

        return executableTool;
    }

    /**
     * Resolve the executable dependencies for the specified project
     * @param executablePomArtifact the project's POM
     * @return a set of Artifacts
     * @throws MojoExecutionException if a failure happens
     */
    private Set resolveExecutableDependencies( Artifact executablePomArtifact )
        throws MojoExecutionException
    {

        Set executableDependencies;
        try
        {
            MavenProject executableProject = this.projectBuilder.buildFromRepository( executablePomArtifact,
                                                                                      this.remoteRepositories,
                                                                                      this.localRepository );

            //get all of the dependencies for the executable project
            List dependencies = executableProject.getDependencies();

            //make Artifacts of all the dependencies
            Set dependencyArtifacts =
                MavenMetadataSource.createArtifacts( this.artifactFactory, dependencies, null, null, null );

            //not forgetting the Artifact of the project itself
            dependencyArtifacts.add( executableProject.getArtifact() );

            //resolve all dependencies transitively to obtain a comprehensive list of assemblies
            ArtifactResolutionResult result = artifactResolver.resolveTransitively( dependencyArtifacts,
                                                                                    executablePomArtifact,
                                                                                    Collections.EMPTY_MAP,
                                                                                    this.localRepository,
                                                                                    this.remoteRepositories,
                                                                                    metadataSource, null,
                                                                                    Collections.EMPTY_LIST );
            executableDependencies = result.getArtifacts();

        }
        catch ( Exception ex )
        {
            throw new MojoExecutionException(
                "Encountered problems resolving dependencies of the executable " + "in preparation for its execution.",
                ex );
        }

        return executableDependencies;
    }

    /**
     * Stop program execution for nn millis.
     *
     * @param millis the number of millis-seconds to wait for,
     *               <code>0</code> stops program forever.
     */
    private void waitFor( long millis )
    {
        Object lock = new Object();
        synchronized ( lock )
        {
            try
            {
                lock.wait( millis );
            }
            catch ( InterruptedException e )
            {
                Thread.currentThread().interrupt(); // good practice if don't throw
                getLog().warn( "Spuriously interrupted while waiting for " + millis + "ms", e );
            }
        }
    }

}
