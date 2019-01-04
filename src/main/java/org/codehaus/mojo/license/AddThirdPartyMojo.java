package org.codehaus.mojo.license;

/*
 * #%L
 * License Maven Plugin
 * %%
 * Copyright (C) 2008 - 2011 CodeLutin, Codehaus, Tony Chemit
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingException;
import org.codehaus.mojo.license.api.MavenProjectDependenciesConfigurator;
import org.codehaus.mojo.license.api.ThirdPartyToolException;
import org.codehaus.mojo.license.model.LicenseMap;
import org.codehaus.mojo.license.utils.FileUtil;
import org.codehaus.mojo.license.utils.MojoHelper;
import org.codehaus.mojo.license.utils.SortedProperties;

// CHECKSTYLE_OFF: LineLength
/**
 * Goal to generate the third-party license file.
 * <p>
 * This file contains a list of the dependencies and their licenses.  Each dependency and its
 * license is displayed on a single line in the format
 * <pre>
 *   (&lt;license-name&gt;) &lt;project-name&gt; &lt;groupId&gt;:&lt;artifactId&gt;:&lt;version&gt; - &lt;project-url&gt;
 * </pre>
 * The directory containing the license database file is added to the classpath as an additional resource.
 *
 * @author tchemit dev@tchemit.fr
 * @since 1.0
 */
// CHECKSTYLE_ON: LineLength
@Mojo( name = "add-third-party", requiresDependencyResolution = ResolutionScope.TEST,
       defaultPhase = LifecyclePhase.GENERATE_RESOURCES )
public class AddThirdPartyMojo extends AbstractAddThirdPartyMojo implements MavenProjectDependenciesConfigurator
{

    // ----------------------------------------------------------------------
    // Mojo Parameters
    // ----------------------------------------------------------------------

    /**
     * To skip execution of this mojo.
     *
     * @since 1.5
     */
    @Parameter( property = "license.skipAddThirdParty", defaultValue = "false" )
    private boolean skipAddThirdParty;

    // ----------------------------------------------------------------------
    // Private Fields
    // ----------------------------------------------------------------------

    /**
     * Internal flag to know if missing file must be generated.
     */
    private boolean doGenerateMissing;

    // ----------------------------------------------------------------------
    // AbstractLicenseMojo Implementaton
    // ----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSkip()
    {
        return skipAddThirdParty;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean checkPackaging()
    {
        if ( acceptPomPackaging )
        {

            // rejects nothing
            return true;
        }

        // can reject pom packaging
        return rejectPackaging( "pom" );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean checkSkip()
    {
        if ( !isDoGenerate() && !isDoGenerateBundle() && !doGenerateMissing )
        {

            getLog().info( "All files are up to date, skip goal execution." );
            return false;
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doAction()
            throws Exception
    {

        consolidate();

        checkUnsafeDependencies();

        boolean safeLicense = checkForbiddenLicenses();

        checkBlacklist( safeLicense );

        writeThirdPartyFile();

        if ( doGenerateMissing )
        {

            writeMissingFile();
        }

        boolean unsafe = CollectionUtils.isNotEmpty( getUnsafeDependencies() );

        checkMissing( unsafe );

        if ( !unsafe && isUseMissingFile() && MapUtils.isEmpty( getUnsafeMappings() ) && getMissingFile().exists() )
        {

            // there is no missing dependencies, but still a missing file, delete it
            getLog().info( "There is no dependency to put in missing file, delete it at " + getMissingFile() );
            FileUtil.deleteFile( getMissingFile() );
        }

        if ( !unsafe && deployMissingFile && MapUtils.isNotEmpty( getUnsafeMappings() ) )
        {

            // can deploy missing file
            File file = getMissingFile();

            getLog().info( "Will attach third party file from " + file );
            getHelper().attachThirdPartyDescriptor( file );
        }

        addResourceDir( getOutputDirectory(), "**/*.txt" );
    }

    // ----------------------------------------------------------------------
    // AbstractAddThirdPartyMojo Implementation
    // ----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    @Override
    protected SortedMap<String, MavenProject> loadDependencies()
    {
        return getHelper().loadDependencies( this );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected SortedProperties createUnsafeMapping()
      throws ProjectBuildingException, IOException, ThirdPartyToolException, MojoExecutionException
    {

        SortedSet<MavenProject> unsafeDependencies = getUnsafeDependencies();

        SortedProperties unsafeMappings =
                getHelper().createUnsafeMapping( getLicenseMap(), getMissingFile(), missingFileUrl,
                                                 useRepositoryMissingFiles, unsafeDependencies,
                                                 getProjectDependencies() );
        if ( isVerbose() )
        {
            getLog().info( "found " + unsafeMappings.size() + " unsafe mappings" );
        }

        // compute if missing file should be (re)-generate
        doGenerateMissing = computeDoGenerateMissingFile( unsafeMappings, unsafeDependencies );

        if ( doGenerateMissing && isVerbose() )
        {
            StringBuilder sb = new StringBuilder();
            sb.append( "Will use from missing file " );
            sb.append( unsafeMappings.size() );
            sb.append( " dependencies :" );
            for ( Map.Entry<Object, Object> entry : unsafeMappings.entrySet() )
            {
                String id = (String) entry.getKey();
                String license = (String) entry.getValue();
                sb.append( "\n - " ).append( id ).append( " - " ).append( license );
            }
            getLog().info( sb.toString() );
        }
        else
        {
            if ( isUseMissingFile() && !unsafeMappings.isEmpty() )
            {
                getLog().info( "Missing file " + getMissingFile() + " is up-to-date." );
            }
        }
        return unsafeMappings;
    }

    // ----------------------------------------------------------------------
    // MavenProjectDependenciesConfigurator Implementaton
    // ----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    public String getExcludedGroups()
    {
        return excludedGroups;
    }

    /**
     * {@inheritDoc}
     */
    public String getIncludedGroups()
    {
        return includedGroups;
    }

    /**
     * {@inheritDoc}
     */
    public List<String> getExcludedScopes()
    {
        return MojoHelper.getParams( excludedScopes );
    }

    /**
     * {@inheritDoc}
     */
    public List<String> getIncludedScopes()
    {
        return MojoHelper.getParams( includedScopes );
    }

    /**
     * {@inheritDoc}
     */
    public List<String> getExcludedTypes()
    {
        return MojoHelper.getParams( excludedTypes );
    }

    /**
     * {@inheritDoc}
     */
    public List<String> getIncludedTypes()
    {
        return MojoHelper.getParams( includedTypes );
    }

    /**
     * {@inheritDoc}
     */
    public String getExcludedArtifacts()
    {
        return excludedArtifacts;
    }

    /**
     * {@inheritDoc}
     */
    public String getIncludedArtifacts()
    {
        return includedArtifacts;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isIncludeTransitiveDependencies()
    {
        return includeTransitiveDependencies;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isExcludeTransitiveDependencies()
    {
        return excludeTransitiveDependencies;
    }



    // ----------------------------------------------------------------------
    // Private Methods
    // ----------------------------------------------------------------------

    /**
     * @param unsafeMappings     the unsafe mapping coming from the missing file
     * @param unsafeDependencies the unsafe dependencies from the project
     * @return {@code true} if missing ifle should be (re-)generated, {@code false} otherwise
     * @throws IOException if any IO problem
     * @since 1.0
     */
    private boolean computeDoGenerateMissingFile( SortedProperties unsafeMappings,
                                                  SortedSet<MavenProject> unsafeDependencies ) throws IOException
    {

        if ( !isUseMissingFile() )
        {

            // never use the missing file
            return false;
        }

        if ( isForce() )
        {

            // the mapping for missing file is not empty, regenerate it
            return !CollectionUtils.isEmpty( unsafeMappings.keySet() );
        }

        if ( !CollectionUtils.isEmpty( unsafeDependencies ) )
        {

            // there is some unsafe dependencies from the project, must
            // regenerate missing file
            return true;
        }

        File missingFile = getMissingFile();

        if ( !missingFile.exists() )
        {

            // the missing file does not exists, this happens when
            // using remote missing file from dependencies
            return true;
        }

        // check if the missing file has changed
        SortedProperties oldUnsafeMappings = new SortedProperties( getEncoding() );
        oldUnsafeMappings.load( missingFile );
        return !unsafeMappings.equals( oldUnsafeMappings );
    }

    /**
     * Write the missing file ({@link #getMissingFile()}.
     *
     * @throws IOException if error while writing missing file
     */
    private void writeMissingFile()
            throws IOException
    {

        Log log = getLog();
        LicenseMap licenseMap = getLicenseMap();
        File file = getMissingFile();

        FileUtil.createDirectoryIfNecessary( file.getParentFile() );
        log.info( "Regenerate missing license file " + file );

        FileOutputStream writer = new FileOutputStream( file );
        try
        {
            StringBuilder sb = new StringBuilder( " Generated by " + getClass().getName() );
            List<String> licenses = new ArrayList<>( licenseMap.keySet() );
            licenses.remove( LicenseMap.UNKNOWN_LICENSE_MESSAGE );
            if ( !licenses.isEmpty() )
            {
                sb.append( "\n-------------------------------------------------------------------------------" );
                sb.append( "\n Already used licenses in project :" );
                for ( String license : licenses )
                {
                    sb.append( "\n - " ).append( license );
                }
            }
            sb.append( "\n-------------------------------------------------------------------------------" );
            sb.append( "\n Please fill the missing licenses for dependencies :\n\n" );
            getUnsafeMappings().store( writer, sb.toString() );
        }
        finally
        {
            writer.close();
        }
    }

    void initFromMojo( AggregatorAddThirdPartyMojo mojo, MavenProject mavenProject,
            Map<String, List<Dependency>> reactorProjects ) throws Exception
    {
        project = mavenProject;
        deployMissingFile = mojo.deployMissingFile;
        useRepositoryMissingFiles = mojo.useRepositoryMissingFiles;
        acceptPomPackaging = mojo.acceptPomPackaging;
        excludedScopes = mojo.excludedScopes;
        includedScopes = mojo.includedScopes;
        excludedGroups = mojo.excludedGroups;
        includedGroups = mojo.includedGroups;
        excludedArtifacts = mojo.excludedArtifacts;
        includedArtifacts = mojo.includedArtifacts;
        includeTransitiveDependencies = mojo.includeTransitiveDependencies;
        excludeTransitiveDependencies = mojo.excludeTransitiveDependencies;
        thirdPartyFilename = mojo.thirdPartyFilename;
        useMissingFile = mojo.useMissingFile;
        String absolutePath = mojo.getProject().getBasedir().getAbsolutePath();

        missingFile = new File( project.getBasedir(),
                mojo.missingFile.getAbsolutePath().substring( absolutePath.length() ) );
        resolvedOverrideUrl  = mojo.resolvedOverrideUrl;
        missingLicensesFileArtifact = mojo.missingLicensesFileArtifact;
        localRepository = mojo.localRepository;
        remoteRepositories = mojo.remoteRepositories;
        dependencies = new HashSet<Artifact>( mavenProject.getDependencies() );
        licenseMerges = mojo.licenseMerges;
        licenseMergesFile = mojo.licenseMergesFile;
        includedLicenses = mojo.includedLicenses;
        excludedLicenses = mojo.excludedLicenses;
        bundleThirdPartyPath = mojo.bundleThirdPartyPath;
        generateBundle = mojo.generateBundle;
        force = mojo.force;
        failIfWarning = mojo.failIfWarning;
        failOnMissing = mojo.failOnMissing;
        failOnBlacklist = mojo.failOnBlacklist;
        sortArtifactByName = mojo.sortArtifactByName;
        fileTemplate = mojo.fileTemplate;
        session = mojo.session;
        verbose = mojo.verbose;
        encoding = mojo.encoding;

        setLog( mojo.getLog() );

        dependenciesTool.loadProjectArtifacts( localRepository, project.getRemoteArtifactRepositories(), project,
                reactorProjects );

        init();

        consolidate();
    }
}
