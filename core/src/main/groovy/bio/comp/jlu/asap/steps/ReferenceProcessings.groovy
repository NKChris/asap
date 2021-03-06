
package bio.comp.jlu.asap.steps


import java.nio.file.*
import groovy.util.logging.Slf4j
import bio.comp.jlu.asap.api.ReferenceType
import bio.comp.jlu.asap.api.RunningStates
import bio.comp.jlu.asap.Step

import static bio.comp.jlu.asap.ASAPConstants.*
import static bio.comp.jlu.asap.api.MiscConstants.*
import static bio.comp.jlu.asap.api.RunningStates.*
import static bio.comp.jlu.asap.api.Paths.*


/**
 *
 * @author Oliver Schwengers <oliver.schwengers@computational.bio.uni-giessen.de>
 */
@Slf4j
class ReferenceProcessings extends Step {

    public static final String STEP_ABBR = 'referenceProcessings'


    private static String SAMTOOLS = "${ASAP_HOME}/share/samtools/bin/samtools"

    private Path referencesPath

    enum Format{
        genbank,
        embl,
        fasta,
        unknown
    }


    ReferenceProcessings( def config ) {

        super( STEP_ABBR, config, true )

        config.steps[ stepName ] = [
            status: INIT.toString()
        ]

    }


    @Override
    boolean isSelected() {

        return true

    }


    @Override
    public void setStatus( RunningStates status ) {

        config.steps[ stepName ].status = status.toString()

    }


    @Override
    public RunningStates getStatus() {

        return RunningStates.getEnum( config.steps[ stepName ].status )

    }


    @Override
    void run() {

        log.trace( "${stepName} running..." )
        config.steps[ stepName ].start = (new Date()).format( DATE_FORMAT )


        try {

            if( check() ) {

                setStatus( SETUP )
                setup()

                setStatus( RUNNING )
                runStep()

                clean()

                setStatus( FINISHED )
                success = true

            } else {
                log.warn( "skip ${stepName} step upon failed check!" )
                success = false
                setStatus( SKIPPED )
            }

        } catch( Throwable ex ) {
            log.error( "${stepName} step aborted!", ex )
            success = false
            setStatus( FAILED )
            config.steps[ stepName ].error = ex.getLocalizedMessage()
        }

        config.steps[ stepName ].end = (new Date()).format( DATE_FORMAT )

    }




    @Override
    boolean check() {

        log.trace( 'check' )
        return true

    }


    @Override
    void setup() throws Throwable {

        log.trace( 'setup' )
        referencesPath = projectPath.resolve( PROJECT_PATH_REFERENCES )

    }


    @Override
    void runStep() throws Throwable {

        log.trace( 'run' )

        config.references.each( { ref ->

            Path referencePath = referencesPath.resolve( ref )
            String fileName = ref.substring( 0, ref.lastIndexOf( '.' ) )

            // standard ASAP reference paths
            Path fastaPath   = referencesPath.resolve( "${fileName}.fasta" )
            Path genbankPath = referencesPath.resolve( "${fileName}.gbk" )

            switch( ReferenceType.getEnum( ref ) ) {

                case ReferenceType.FASTA:
                    log.debug( "move ${referencePath} -> ${fastaPath}" )
                    Files.move( referencePath, fastaPath, StandardCopyOption.REPLACE_EXISTING )
                    break

                case ReferenceType.GENBANK:
                    // rename genbank file suffix to ".gbk"
                    Files.move( referencePath, genbankPath )
                    log.debug( "genbank: ${genbankPath}, fileName: ${fileName}, fasta: ${fastaPath}" )
                    String script = /
from Bio import SeqIO
SeqIO.convert( "${genbankPath}", "${Format.genbank}", "${fastaPath}", "${Format.fasta}" )
/
                    try { // start gbk -> fasta conversion process
                        ProcessBuilder pb = new ProcessBuilder( '/usr/bin/env', 'python',
                            '-c', script )
                        log.info( "exec: ${pb.command()}" )
                        log.info( '----------------------------------------------------------------------------------------------' )
                        int exitCode = pb.start().waitFor()
                        if( exitCode != 0 )  throw new IllegalStateException( "exitCode = ${exitCode}" )
                        log.info( '----------------------------------------------------------------------------------------------' )
                    } catch( Throwable t ) {
                        log.error( 'genbank->fasta conversion failed!', t )
                        System.exit( 1 )
                    }
                    break

                case ReferenceType.EMBL:
                    log.debug( "embl: ${referencePath}, fileName: ${fileName}, genbank: ${genbankPath}" )
                    String script = /
from Bio import SeqIO
SeqIO.convert( "${referencePath}", "${Format.embl}", "${genbankPath}", "${Format.genbank}" )
/
                    try { // start embl -> gbk conversion process
                        ProcessBuilder pb = new ProcessBuilder( '/usr/bin/env', 'python',
                            '-c', script )
                        log.info( "exec: ${pb.command()}" )
                        log.info( '----------------------------------------------------------------------------------------------' )
                        int exitCode = pb.start().waitFor()
                        if( exitCode != 0 )  throw new IllegalStateException( "exitCode = ${exitCode}" )
                        log.info( '----------------------------------------------------------------------------------------------' )
                    } catch( Throwable t ) {
                        log.error( 'embl->genbank conversion failed!', t )
                        System.exit( 1 )
                    }
                    log.debug( "genbank: ${genbankPath}, fileName: ${fileName}, fasta: ${fastaPath}" )
                    script = /
from Bio import SeqIO
SeqIO.convert( "${genbankPath}", "${Format.genbank}", "${fastaPath}", "${Format.fasta}" )
/
                    try { // start gbk -> fasta conversion process
                        ProcessBuilder pb = new ProcessBuilder( '/usr/bin/env', 'python',
                            '-c', script )
                        log.info( "exec: ${pb.command()}" )
                        log.info( '----------------------------------------------------------------------------------------------' )
                        int exitCode = pb.start().waitFor()
                        if( exitCode != 0 )  throw new IllegalStateException( "exitCode = ${exitCode}" )
                        log.info( '----------------------------------------------------------------------------------------------' )
                    } catch( Throwable t ) {
                        log.error( 'genbank->fasta conversion failed!', t )
                        System.exit( 1 )
                    }
                    break
            }


            // remove possible accession version suffix from fasta header
            StringBuilder sb = new StringBuilder( 10000000 )
            fastaPath.eachLine { line ->
                if( line ==~ /^>.*/ ) {
                    def header = line.split( ' ' )
                    def locus = header[0]
                    header -= locus
                    locus = locus.substring(1)
                    if( locus.contains('.') ) {
                        locus = locus.split( '\\.' )[0]
                        sb.append( ">${locus} ${header.join(' ')}\n" )
                    } else sb.append( ">${locus} ${header.join(' ')}\n" )
                } else sb.append( line ).append('\n')
            }
            fastaPath.text = sb.toString()


            try { // start fasta index creation process
                ProcessBuilder pb = new ProcessBuilder(
                    SAMTOOLS, 'faidx',
                    fastaPath.toString() )
                log.info( "exec: ${pb.command()}" )
                log.info( '----------------------------------------------------------------------------------------------' )
                int exitCode = pb.start().waitFor()
                if( exitCode != 0 )  throw new IllegalStateException( "exitCode = ${exitCode}" )
                log.info( '----------------------------------------------------------------------------------------------' )
            } catch( Throwable t ) {
                log.error( 'fasta index creation failed!', t )
                System.exit( 1 )
            }
        } )

    }


    @Override
    void clean() throws Throwable {

        log.trace( 'clean' )

    }

}

