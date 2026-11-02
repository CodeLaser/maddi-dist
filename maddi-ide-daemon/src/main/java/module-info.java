module io.codelaser.maddi.ide.daemon {
    requires io.codelaser.maddi.callgraph;
    // the modification analysis, as a service: the implementation is provided at run time (maddi-run-analysis)
    requires io.codelaser.maddi.analysis.api;
    requires io.codelaser.maddi.cst.analysis;
    requires io.codelaser.maddi.cst.api;
    // maddi's Java-to-Kotlin translator, for the IDE's "Translate to Kotlin with maddi"
    requires io.codelaser.maddi.cst.print.kotlin;
    requires io.codelaser.maddi.inspection.api;
    requires io.codelaser.maddi.inspection.openjdk;
    requires io.codelaser.maddi.inspection.resource;
    // the mixed Java+Kotlin parse, and the realm the Kotlin front end is loaded in (automatic modules: Kotlin)
    requires io.codelaser.maddi.inspection.mixed;
    requires io.codelaser.maddi.kotlin.api;
    requires io.codelaser.maddi.kotlin.realm;
    requires io.codelaser.maddi.support;
    requires io.codelaser.maddi.run.config;
    requires io.codelaser.maddi.run.openjdkmain;
    // a compile log turned into a configuration, as the CLI's --compile-log does (writeInputConfiguration)
    requires io.codelaser.maddi.project.config;

    requires com.fasterxml.jackson.databind;
    requires java.management; // the heap and GC share on the progress line (HeapSampler)
    requires org.slf4j;

    exports io.codelaser.maddi.ide.daemon;
}
