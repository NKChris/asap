// always invoke this script via $ASAP_HOME/bin/groovy

/**********************
 *** Script Imports ***
**********************/


import java.nio.file.*
import groovy.util.CliBuilder
import groovy.json.*
import org.slf4j.LoggerFactory
import bio.comp.jlu.asap.api.DataType
import bio.comp.jlu.asap.api.FileType

import static bio.comp.jlu.asap.api.MiscConstants.*
import static bio.comp.jlu.asap.api.Paths.*




/************************
 *** Script Constants ***
************************/
final def env = System.getenv()
ASAP_HOME = env.ASAP_HOME
ASAP_DB   = env.ASAP_DB

SAMTOOLS = "${ASAP_HOME}/share/samtools/bin/samtools"
BOWTIE2  = "${ASAP_HOME}/share/bowtie2/bowtie2"
PBALIGN  = "${ASAP_HOME}/share/smrtlink/smrtcmds/bin/pbalign"

SAMTOOLS_SORT_MEM     = '16G' // max-ram usage until tmp-file is created during sorting (optimum 4G for avg. files)
NUM_THREADS = '8'


/*********************
 *** Script Params ***
*********************/


log = LoggerFactory.getLogger( getClass().getName() )

def cli = new CliBuilder( usage: 'asap-mapping-bowtie2.groovy --project-path <project-path> --genome-id <genome-id>' )
    cli.p( longOpt: 'project-path', args: 1, argName: 'project-path', required: true, 'Path to ASAP project' )
    cli.g( longOpt: 'genome-id', args: 1, argName: 'genome-id', required: false, 'ID within the ASAP project' )
def opts = cli.parse( args )
if( !opts?.p ) {
    log.error( 'no project path provided!' )
    System.exit( 1 )
}

def genomeId
if( opts?.g  &&  opts.g ==~ /\d+/ )
    genomeId = Integer.parseInt( opts.g )
else if( env.SGE_TASK_ID  &&  env.SGE_TASK_ID ==~ /\d+/ )
    genomeId = Integer.parseInt( env.SGE_TASK_ID )
else {
    log.error( 'no genome id provided!' )
    System.exit( 1 )
}

// log system environment vars and Java properties
log.info( "SCRIPT: ${getClass().protectionDomain.codeSource.location.path}" )
log.info( "USER: ${env.USER}" )
log.info( "CWD: ${env.PWD}" )
log.info( "HOSTNAME: ${env.HOSTNAME}" )
log.info( "ASAP_HOME: ${env.ASAP_HOME}" )
log.info( "ASAP_DB: ${env.ASAP_DB}" )
log.info( "PATH: ${env.PATH}" )
def props = System.getProperties()
log.info( "script.name: ${props['script.name']}" )
log.info( "groovy.home: ${props['groovy.home']}" )
log.info( "file.encoding: ${props['file.encoding']}" )
log.info( "genome-id: ${genomeId}" )




/********************
 *** Script Paths ***
********************/


Path rawProjectPath = Paths.get( opts.p )
if( !Files.exists( rawProjectPath ) ) {
    println( "Error: project directory (${rawProjectPath}) does not exist!" )
    System.exit(1)
}
final Path projectPath = rawProjectPath.toRealPath()
log.info( "project-path: ${projectPath}")


// read config json
Path configPath = projectPath.resolve( 'config.json' )
if( !Files.isReadable( configPath ) ) {
    log.error( 'config.json not readable!' )
    System.exit( 1 )
}
def config = (new JsonSlurper()).parseText( projectPath.resolve( 'config.json' ).text )


final def genome = config.genomes.find( { it.id == genomeId } )
if( !genome ) {
    log.error( "no genome found in config! genome-id=${genomeId}" )
    System.exit( 1 )
}
final String genomeName = "${config.project.genus}_${genome.species}_${genome.strain}"
log.info( "genome-name: ${genomeName}")


final Path mappingsPath = projectPath.resolve( PROJECT_PATH_MAPPINGS )
Files.createFile( mappingsPath.resolve( "${genomeName}.running" ) ) // create state.running


// create local tmp directory
final Path tmpPath = Paths.get( '/', 'var', 'scratch', "tmp-${System.currentTimeMillis()}-${Math.round(Math.random()*1000)}" )
try { // create tmp dir
    log.info( "tmp-folder: ${tmpPath}" )
    Files.createDirectory( tmpPath )
} catch( Throwable t ) {
    terminate( "could create tmp dir! gid=${genomeId}, tmp-dir=${tmpPath}", t, genomeQCReadsPath, tmpPath )
}


// create info object
def info = [
    time: [
        start: (new Date()).format( DATE_FORMAT )
    ],
    genome: [
        id: genome.id,
        species: genome.species,
        strain: genome.strain
    ],
    path: mappingsPath.toString()
]




/********************
 *** Script Logic ***
********************/


// select reads (paired-end preffered)
def reads = null
genome.data.each( {
    FileType ft = FileType.getEnum( it.type )
    if( ft  &&  ft.getDataType() == DataType.READS ) {
        if( !reads ) reads = it
        else {
            if( ft == FileType.READS_PAIRED_END )
                reads = it
        }
    }
} )
if( reads == null ) {
    log.error( 'no reads provided!' )
    Files.move( mappingsPath.resolve( "${genomeName}.running" ), mappingsPath.resolve( "${genomeName}.failed" ) ) // set state-file to failed
    System.exit( 0 )
}


// mapping process
Path genomeMappingsPath = mappingsPath.resolve( "${genomeName}.bam" )
Path readsDirPath = Paths.get( projectPath.toString(), PROJECT_PATH_READS_QC, genomeName )
Path referenceFilePath = Paths.get( projectPath.toString(), PROJECT_PATH_REFERENCES, config.references[0] )
String fileName = referenceFilePath.fileName.toString().substring( 0, referenceFilePath.fileName.toString().lastIndexOf( '.' ) )
FileType ft = FileType.getEnum( reads.type )
if( ft == FileType.READS_PAIRED_END  ||  ft == FileType.READS_SINGLE  ||  ft == FileType.READS_SANGER ) {

    String mappingReadsParameter
    if( ft == FileType.READS_PAIRED_END )
        mappingReadsParameter = "-1 ${readsDirPath.resolve( reads.files[0] )} -2 ${readsDirPath.resolve( reads.files[1] )}"
    else
        mappingReadsParameter = "-U ${readsDirPath.resolve( reads.files[0] )}"

    ProcessBuilder pb = new ProcessBuilder( 'sh', '-c',
        "${BOWTIE2} --sensitive --threads ${NUM_THREADS} --rg-id ${genomeName} --rg SM:${genomeName} -x ${projectPath}/references/${fileName} ${mappingReadsParameter} 2>${mappingsPath}/${genomeName}.bt2.log | ${SAMTOOLS} view -u - 2>/dev/null | ${SAMTOOLS} sort --threads ${NUM_THREADS} -m ${SAMTOOLS_SORT_MEM} -T ${genomeName} -o ${genomeMappingsPath.toString()} - 2>/dev/null" )
        .redirectErrorStream( true )
        .redirectOutput( ProcessBuilder.Redirect.INHERIT )
        .directory( mappingsPath.toFile() )
    log.info( "exec: ${pb.command()}" )
    log.info( '----------------------------------------------------------------------------------------------' )
    if( pb.start().waitFor() != 0 ) terminate( 'could not exec bowtie2|samtools view|samtools sort!', mappingsPath, genomeName )
    log.info( '----------------------------------------------------------------------------------------------' )

    pb = new ProcessBuilder( SAMTOOLS,
        'index',
        genomeMappingsPath.toString() )
        .redirectErrorStream( true )
        .redirectOutput( ProcessBuilder.Redirect.INHERIT )
        .directory( mappingsPath.toFile() )
    log.info( "exec: ${pb.command()}" )
    log.info( '----------------------------------------------------------------------------------------------' )
    if( pb.start().waitFor() != 0 ) terminate( 'could not exec samtools index!', mappingsPath, genomeName )
    log.info( '----------------------------------------------------------------------------------------------' )


    // parse and store mapping stats
    def stat = [:]
    String bt2Logs = mappingsPath.resolve( "${genomeName}.bt2.log" ).toFile().text
    try {
        def m = bt2Logs =~ /(\d+) reads; of these:/
        stat.reads = m[0][1] as long
        m = bt2Logs =~ /  \d+ .+ were (paired|unpaired);/
        stat.isPairedEnd = m[0][1] == 'paired'
        if( stat.isPairedEnd ) { // paired-end
            m = bt2Logs =~  /(\d+) \(.+\) aligned concordantly 0 times/
            stat.unmapped = m[0][1] as long
            m = bt2Logs =~  /(\d+) \(.+\) aligned concordantly exactly 1 time/
            stat.unique = m[0][1] as long
            m = bt2Logs =~  /(\d+) \(.+\) aligned concordantly >1 times/
            stat.multiple = m[0][1] as long
        } else { // single end
            m = bt2Logs =~  /(\d+) \(.+\) aligned 0 times/
            stat.unmapped = m[0][1] as long
            m = bt2Logs =~  /(\d+) \(.+\) aligned exactly 1 time/
            stat.unique = m[0][1] as long
            m = bt2Logs =~  /(\d+) \(.+\) aligned >1 times/
            stat.multiple = m[0][1] as long
        }
        stat.ratio = (stat.reads - stat.unmapped) / stat.reads
        info << stat
    } catch( Exception ex ) {
        terminate( 'Could not parse bowtie2 log file!', ex, mappingsPath, genomeName )
    }

} else if( ft == FileType.READS_PACBIO_RSII  ||  ft == FileType.READS_PACBIO_SEQUEL ) {

    ProcessBuilder pb = new ProcessBuilder( PBALIGN,
        '--nproc', NUM_THREADS,
        readsDirPath.resolve( "${genomeName}.subreads.bam" ).toString(), // bam format input file
        "${projectPath}/references/${fileName}.fasta".toString(), // reference file
        genomeMappingsPath.toString() ) // output file
        .redirectErrorStream( true )
        .redirectOutput( ProcessBuilder.Redirect.INHERIT )
        .directory( mappingsPath.toFile() )
    log.info( "exec: ${pb.command()}" )
    log.info( '----------------------------------------------------------------------------------------------' )
    if( pb.start().waitFor() != 0 ) terminate( 'could not exec pbalign!', mappingsPath, genomeName )
    log.info( '----------------------------------------------------------------------------------------------' )

    // create, parse and store unique/multi mapping stats
    Path mappingsStatsPath = mappingsPath.resolve( "${genomeName}.pbalign.log" )
    pb = new ProcessBuilder( 'sh', '-c',
        "${SAMTOOLS} view -@ ${NUM_THREADS} ${genomeMappingsPath.toString()} | cut -f1 | sort | uniq -c | tr -s ' ' | cut -d ' ' -f2 | uniq -c > ${mappingsStatsPath.toString()}" )
        .redirectErrorStream( true )
        .redirectOutput( ProcessBuilder.Redirect.INHERIT )
        .directory( mappingsPath.toFile() )
    log.info( "exec: ${pb.command()}" )
    log.info( '----------------------------------------------------------------------------------------------' )
    if( pb.start().waitFor() != 0 ) terminate( 'could not exec samtools view|cut|sort|uniq|tr|cut|uniq!', mappingsPath, genomeName )
    log.info( '----------------------------------------------------------------------------------------------' )

    int noUniqMappings  = 0
    int noMultiMappings = 0
    mappingsStatsPath.text.eachLine( { line ->
        def cols = line.replaceAll( '\\s+', ' ' ).split( ' ' )
        int count = cols[1] as int
        int frequency = cols[2] as int
        if( frequency == 1 )
            noUniqMappings = count
        else
            noMultiMappings += count
    } )

    // extract number of total reads from QC stats
    Path qcGenomeStatsPath = Paths.get( projectPath.toString(), PROJECT_PATH_READS_QC, genomeName, 'info.json' )
    def qcStats = (new JsonSlurper()).parseText( qcGenomeStatsPath.text )

    def stat = [
        reads: qcStats.qcReadsAvg.noReads,
        unique: noUniqMappings,
        multiple: noMultiMappings,
        unmapped: qcStats.qcReadsAvg.noReads - noUniqMappings - noMultiMappings
    ]
    stat.ratio = (stat.reads - stat.unmapped) / stat.reads
    info << stat


} else {
    log.error( 'no reads with supported type provided!' )
    Files.move( mappingsPath.resolve( "${genomeName}.running" ), mappingsPath.resolve( "${genomeName}.failed" ) ) // set state-file to failed
    System.exit( 0 )
}






// cleanup
log.debug( 'delete tmp-dir' )
if( !tmpPath.deleteDir() ) terminate( "could not recursively delete tmp-dir=${tmpPath}", genomeQCReadsPath, tmpPath )


// store info.json
info.time.end = (new Date()).format( DATE_FORMAT )
File infoJson = mappingsPath.resolve( "${genomeName}.json" ).toFile()
infoJson << JsonOutput.prettyPrint( JsonOutput.toJson( info ) )


// set state-file to finished
Files.move( mappingsPath.resolve( "${genomeName}.running" ), mappingsPath.resolve( "${genomeName}.finished" ) )




/**********************
 *** Script Methods ***
**********************/


private void terminate( String msg, Path mappingsPath, String genomeName ) {
    terminate( msg, null, mappingsPath, genomeName )
}

private void terminate( String msg, Throwable t, Path mappingsPath, String genomeName ) {

    if( t ) log.error( msg, t )
    else    log.error( msg )
    Files.move( mappingsPath.resolve( "${genomeName}.running" ), mappingsPath.resolve( "${genomeName}.failed" ) ) // set state-file to failed
    System.exit( 1 )

}
