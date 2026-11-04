package com.twinguard.fmu;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * An FMI 2.0 Co-Simulation FMU: the standard way to package software from another tool or vendor.
 *
 * An FMU contains native code, and loading one runs that code with this program's permissions,
 * so TwinGuard only loads files someone placed in its FMU folder, and only from a local path.
 */
public final class FmuFile {

    /** The FMI 2.0 C interface, as exported by every FMU's library. */
    public interface Fmi2 extends Library {
        Pointer fmi2Instantiate(String name, int type, String guid, String resources, Callbacks callbacks, byte visible, byte logging);

        int fmi2SetupExperiment(Pointer c, int toleranceDefined, double tolerance, double start, int stopDefined, double stop);

        int fmi2EnterInitializationMode(Pointer c);

        int fmi2ExitInitializationMode(Pointer c);

        int fmi2SetReal(Pointer c, int[] references, long count, double[] values);

        int fmi2GetReal(Pointer c, int[] references, long count, double[] values);

        int fmi2DoStep(Pointer c, double time, double step, byte noSetPrior);

        int fmi2Terminate(Pointer c);

        void fmi2FreeInstance(Pointer c);
    }

    /** The FMU can call this to log. TwinGuard ignores the message, and only provides a valid function. */
    public interface Logger extends Callback {
        void invoke(Pointer environment, String instance, int status, String category, String message);
    }

    @Structure.FieldOrder({"logger", "allocateMemory", "freeMemory", "stepFinished", "environment"})
    public static class Callbacks extends Structure implements Structure.ByReference {
        public Logger logger;
        public Pointer allocateMemory;
        public Pointer freeMemory;
        public Pointer stepFinished;
        public Pointer environment;
    }

    private static final int CO_SIMULATION = 1;
    private static final long MAX_UNPACKED_BYTES = 512L * 1024 * 1024;
    private static final Logger LOGGER = (environment, instance, status, category, message) -> { };
    private static final Callbacks CALLBACKS = callbacks();

    private static Callbacks callbacks() {
        NativeLibrary c = NativeLibrary.getInstance(com.sun.jna.Platform.isWindows() ? "msvcrt" : "c");
        Callbacks callbacks = new Callbacks();
        callbacks.logger = LOGGER;
        callbacks.allocateMemory = c.getFunction("calloc");
        callbacks.freeMemory = c.getFunction("free");
        callbacks.write();
        return callbacks;
    }

    private final Path source;
    private final String modelName;
    private final String description;
    private final String guid;
    private final String resources;
    private final Map<String, Integer> inputs;
    private final Map<String, Integer> outputs;
    private final Fmi2 library;

    private FmuFile(Path source, String modelName, String description, String guid, String resources,
                    Map<String, Integer> inputs, Map<String, Integer> outputs, Fmi2 library) {
        this.source = source;
        this.modelName = modelName;
        this.description = description;
        this.guid = guid;
        this.resources = resources;
        this.inputs = inputs;
        this.outputs = outputs;
        this.library = library;
    }

    /** Reads the FMU's description and checks it without loading any of its native code. */
    public static Description describe(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry("modelDescription.xml");
            if (entry == null) {
                throw new FmuException(file.getFileName() + " has no modelDescription.xml, so it isn't an FMU");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return parse(file, in);
            }
        } catch (IOException e) {
            throw new FmuException(file.getFileName() + " can't be read as an FMU: " + e.getMessage(), e);
        }
    }

    public record Description(String modelName, String description, String modelIdentifier, String guid,
                              Map<String, Integer> inputs, Map<String, Integer> outputs) {
    }

    private static Description parse(Path file, InputStream in) {
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            doc = factory.newDocumentBuilder().parse(in);
        } catch (Exception e) {
            throw new FmuException(file.getFileName() + " has an invalid modelDescription.xml: " + e.getMessage(), e);
        }
        Element root = doc.getDocumentElement();
        if (!root.getAttribute("fmiVersion").startsWith("2.")) {
            throw new FmuException(file.getFileName() + " uses FMI " + root.getAttribute("fmiVersion") + ". TwinGuard supports FMI 2.0");
        }
        NodeList coSim = root.getElementsByTagName("CoSimulation");
        if (coSim.getLength() == 0) {
            throw new FmuException(file.getFileName() + " doesn't support Co-Simulation. TwinGuard needs a Co-Simulation FMU");
        }
        Map<String, Integer> inputs = new LinkedHashMap<>();
        Map<String, Integer> outputs = new LinkedHashMap<>();
        NodeList variables = root.getElementsByTagName("ScalarVariable");
        for (int i = 0; i < variables.getLength(); i++) {
            Element v = (Element) variables.item(i);
            if (v.getElementsByTagName("Real").getLength() == 0) {
                continue;
            }
            int reference = Integer.parseInt(v.getAttribute("valueReference"));
            if ("input".equals(v.getAttribute("causality"))) {
                inputs.put(v.getAttribute("name"), reference);
            } else if ("output".equals(v.getAttribute("causality"))) {
                outputs.put(v.getAttribute("name"), reference);
            }
        }
        return new Description(root.getAttribute("modelName"), root.getAttribute("description"),
                ((Element) coSim.item(0)).getAttribute("modelIdentifier"), root.getAttribute("guid"), inputs, outputs);
    }

    /** Unpacks the FMU and loads its native library. This runs code from the file. */
    public static FmuFile load(Path file) {
        Description d = describe(file);
        try {
            Path dir = Files.createTempDirectory("twinguard-fmu-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteQuietly(dir)));
            unpack(file, dir);
            String platform = com.sun.jna.Platform.isWindows() ? "win64" : "linux64";
            String extension = com.sun.jna.Platform.isWindows() ? ".dll" : ".so";
            Path binary = dir.resolve("binaries").resolve(platform).resolve(d.modelIdentifier() + extension);
            if (!Files.exists(binary)) {
                throw new FmuException(file.getFileName() + " has no binary for " + platform);
            }
            Fmi2 library = Native.load(binary.toAbsolutePath().toString(), Fmi2.class);
            return new FmuFile(file, d.modelName(), d.description(), d.guid(), dir.resolve("resources").toUri().toString(), d.inputs(), d.outputs(), library);
        } catch (IOException e) {
            throw new FmuException("Couldn't unpack " + file.getFileName() + ": " + e.getMessage(), e);
        } catch (UnsatisfiedLinkError e) {
            if (mentionsApplicationControl(e)) {
                throw new FmuException("Windows blocked " + file.getFileName() + " with an Application Control policy, such as Smart App Control. "
                        + "TwinGuard doesn't bypass this. A signed FMU, or an administrator's decision about the policy, is needed to run it on this PC.", e, true);
            }
            throw new FmuException("Couldn't load the native library in " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static boolean mentionsApplicationControl(Throwable error) {
        if (String.valueOf(error.getMessage()).contains("Application Control")) {
            return true;
        }
        for (Throwable suppressed : error.getSuppressed()) {
            if (String.valueOf(suppressed.getMessage()).contains("Application Control")) {
                return true;
            }
        }
        return false;
    }

    private static void unpack(Path file, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        long total = 0;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path output = root.resolve(entry.getName().replace('\\', '/')).normalize();
                if (!output.startsWith(root)) {
                    throw new FmuException(file.getFileName() + " tries to write outside its own folder: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }
                Files.createDirectories(output.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    total += Files.copy(in, output, StandardCopyOption.REPLACE_EXISTING);
                }
                if (total > MAX_UNPACKED_BYTES) {
                    throw new FmuException(file.getFileName() + " unpacks to more than 512 MB");
                }
            }
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // A library that's still loaded can't be deleted. It's only a temporary file.
        }
    }

    /** Checks that this FMU offers the signals a feature needs, and gives a clear reason if not. */
    public void requireContract(List<String> inputNames, List<String> outputNames) {
        List<String> missing = new ArrayList<>();
        inputNames.stream().filter(n -> !inputs.containsKey(n)).forEach(n -> missing.add("input " + n));
        outputNames.stream().filter(n -> !outputs.containsKey(n)).forEach(n -> missing.add("output " + n));
        if (!missing.isEmpty()) {
            throw new FmuException(source.getFileName() + " is missing " + String.join(", ", missing));
        }
    }

    public String modelName() {
        return modelName;
    }

    public String description() {
        return description;
    }

    /** One running copy of the software, with its own state. */
    public final class Instance implements AutoCloseable {
        private final Pointer component;
        private final int[] inputRefs;
        private final int[] outputRefs;
        private final double[] outputs;
        private double time;
        private boolean closed;

        Instance(List<String> inputNames, List<String> outputNames) {
            inputRefs = inputNames.stream().mapToInt(FmuFile.this.inputs::get).toArray();
            outputRefs = outputNames.stream().mapToInt(FmuFile.this.outputs::get).toArray();
            outputs = new double[outputRefs.length];
            component = library.fmi2Instantiate("twinguard", CO_SIMULATION, guid, resources, CALLBACKS, (byte) 0, (byte) 0);
            if (component == null) {
                throw new FmuException(source.getFileName() + " refused to create an instance");
            }
            check(library.fmi2SetupExperiment(component, 0, 0, 0, 0, 0), "set up");
            check(library.fmi2EnterInitializationMode(component), "enter initialization");
            check(library.fmi2ExitInitializationMode(component), "finish initialization");
        }

        /** Sets the inputs, advances the software by this much time, and returns its outputs. */
        public double[] step(double[] inputValues, double seconds) {
            check(library.fmi2SetReal(component, inputRefs, inputRefs.length, inputValues), "accept inputs");
            check(library.fmi2DoStep(component, time, seconds, (byte) 1), "run a step");
            time += seconds;
            check(library.fmi2GetReal(component, outputRefs, outputRefs.length, outputs), "return outputs");
            return outputs;
        }

        private void check(int status, String action) {
            // 0 is OK and 1 is a warning. Anything else is a failure of the software under test.
            if (status > 1) {
                throw new FmuException(source.getFileName() + " failed to " + action + ", with status " + status);
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                library.fmi2Terminate(component);
                library.fmi2FreeInstance(component);
            }
        }
    }

    public Instance newInstance(List<String> inputNames, List<String> outputNames) {
        requireContract(inputNames, outputNames);
        return new Instance(inputNames, outputNames);
    }
}
