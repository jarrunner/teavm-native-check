<p align="center"><img src="https://raw.githubusercontent.com/jarrunner/jr/main/icon/jr-icon.svg" width="96" alt="jr"></p>

# teavm-native-check

For Java compiled to C with [TeaVM](https://teavm.org): a javac plugin that makes the rules for native memory compile errors, so a dangling pointer, a leaked handle or an unchecked failure value is caught when you build, not when it crashes.

- **Ten checks** (NC1 to NC10): pointers that outlive their scope, OS handles not closed on every path, failure values used before they are tested, callbacks that would run Java on a foreign thread, and more.
- **Every message says what is wrong, why it matters, and what to write instead.**
- **Compile time only.** It adds nothing to the program and needs nothing beyond the JDK.
- **Proven on a real program:** [jr](https://github.com/jarrunner/jr), a native launcher written in Java, builds with it on every compile.

**Try it:** it is on Maven Central as `io.github.jarrunner:teavm-native-check:1.1.0`. Add it to the compiler's `annotationProcessorPaths` as shown in [Using it in a project](#using-it-in-a-project).

[What it checks](#what-it-checks) · [Using it](#using-it-in-a-project) · [jr](https://github.com/jarrunner/jr) · [jarrunner.github.io](https://jarrunner.github.io)

## What it is

A javac plugin that checks native memory use in Java code compiled to C by TeaVM. In TeaVM, ordinary Java is already memory-safe: the C backend inserts null and bounds checks, and the GC owns objects. The unsafe part is `org.teavm.interop.Address`, a bare pointer. This plugin turns the rules for using it safely into compile errors, so they no longer depend on review. It runs only at compile time and adds nothing to the program.

It does not make native code as safe as Rust. It checks one method at a time against the signatures of what it calls, which catches the common mistakes, and leaves a short, greppable list of `@Unsafe` methods for a person to read. What it does not do: check data crossing OS threads (beyond NC9), follow a resource or a failure value handed out through an out-parameter.

## What it checks

- **NC1** A pointer must not outlive its scope: no `Address` field (except one marked `@Handle`, holding an OS handle), nothing returned out of a `memScoped` block, nothing stored from inside a block into something declared outside it.
- **NC2** No `Address.ofObject`, no arrays of pointers, and no pointer as a type argument (`Optional<Address>`, `List<Address>`: measured to crash once such an object lives across a collection). `Address.ofData` only in the classes listed as `raw` or in a method marked `@Unsafe("reason")`: it is safe only while a live local variable still refers to the array after every use of the address (TeaVM's collector does not move or free an object a stack root points at), and it breaks when the array is not used again, when the address is stored in a field, or when the native call can run Java code. The checker cannot see that liveness, so ordinary code copies into native memory instead; where the copy matters (large buffers), use ofData in an `@Unsafe` method and keep the array live, or call `Address.pin(array)` after the last use.
- **NC3** A method marked `@Scoped` (it allocates memory that piles up) is called only inside a scope, or from another `@Scoped` method.
- **NC4** Raw access (`add`, `getInt`, `putByte`, ...), pointers made from numbers (`Address.fromLong`), casts between a pointer and an object, and calls to `trust=` methods happen only in the classes listed as `raw`, or in a method or class marked `@Unsafe("reason")`. The reason is required.
- **NC5** Nothing that can suspend a TeaVM fiber (`Thread.sleep`, `join`, `yield`, `Object.wait`, `@Async` methods, `synchronized`) inside a scope: fibers share one scope allocator.
- **NC6** A pointer to one C type is not passed where another is expected, from `@CType` annotations (jextract_teavm writes them on bindings) and from `alloc(X.SIZE)`.
- **NC7** A pointer parameter is borrowed unless marked: it, or a pointer derived from it, is not returned (unless `@Returned`), stored, or passed on to a parameter that keeps it (unless `@Escapes`). An override may not add `@Escapes`. Memory from a scope does not reach an `@Escapes` parameter.
- **NC8** An OS resource (a handle, a FILE*, a module) is closed on every path out of the method that opened it: returns, the end, and every exception path (a `throw`, or a call to a method that declares what it throws; a `finally` covers them all). "Opened" is a call marked `@Acquires("close1,close2")`, which names the calls that close it (jextract_teavm writes it from `releases=` in the symbols file). Handing it on also counts: returning it (the method is then `@Acquires` itself), storing it in a field, or passing it to a parameter marked `@Owns("close")` or to a `takes=` method. The branch where it was compared with `==` (`h.toLong() == 0`, `h == INVALID`) is the failed open. Methods let the `tracked=` exceptions out only if they declare them (`throws`, free at run time), so every path is visible one method at a time.
- **NC9** Every Java method handed to C as a callback (`Function.get(Fn.class, Owner.class, "name")`) says which OS thread calls it: `@SameThread("EnumResourceNamesW, before it returns")` or `@ForeignThread("which thread calls it")`. No TeaVM-managed code may run on another OS thread (one shadow stack, one heap, no locks), so a `@ForeignThread` method must also be `@org.teavm.interop.Unmanaged`, and it opens no scope and suspends no fiber: OS calls and primitive values only. Only the marked method's own body is checked.
- **NC10** Rust's `Option`, at compile time. A method marked `@Fails("NULL")` or `@Fails("INVALID_HANDLE_VALUE")` (jextract_teavm writes it from `fails=` in the symbols file) returns its failure value when it fails. Its result may only be compared, returned (the method is then `@Fails` itself), stored, or passed to a parameter marked `@Nullable`, until it has been tested against that value; testing against the wrong one (`== 0` for a function that fails with INVALID_HANDLE_VALUE) does not count. A `@Nullable` parameter is unchecked inside its own method too, so the method that accepts the failure value is checked to handle it. Nothing is added to the program.

Every message says what is wrong, why it matters, and what to write instead:

    N.java:12: error: [NC7-store] Pointer parameter 'p' is stored in 'kept', but it is only borrowed.
      Why: The caller may free that memory as soon as this call returns; the stored copy then points at freed memory.
      Fix: Mark the parameter @Escapes, so every caller sees that the pointer is kept. Or store a Java copy of what it points to.

## Using it in a project

1. It is on Maven Central as `io.github.jarrunner:teavm-native-check` (JDK 21 or newer; no dependencies beyond the JDK's own `jdk.compiler`). To build it yourself: `mvn install`.
2. Define the annotations in your own code, with SOURCE retention so they cost nothing: `Handle`, `Unsafe(String value)`, `Scoped`, `CType(String value)`, `Escapes`, `Returned`, and for NC8 `Acquires(String value)` and `Owns(String value)`, for NC9 `ForeignThread(String value)` and `SameThread(String value)`, for NC10 `Fails(String value)` and `Nullable`. The names can be changed through arguments.
3. Run javac with the plugin. With Maven:

        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId>
          <configuration>
            <annotationProcessorPaths>
              <path><groupId>io.github.jarrunner</groupId><artifactId>teavm-native-check</artifactId><version>1.1.0</version></path>
            </annotationProcessorPaths>
            <compilerArgs><arg>-Xplugin:NativeCheck scope=my.N.memScoped alloc=my.N.alloc raw=my.N,my.Bindings</arg></compilerArgs>
          </configuration>
        </plugin>

   With plain javac: `javac -processorpath teavm-native-check-1.1.0.jar "-Xplugin:NativeCheck scope=..." ...`.

Arguments (`key=value`, lists comma separated), all in `Config.java`:
- `scope`: the method(s) whose lambda argument is a scope (`pkg.N.memScoped`).
- `raw`: classes allowed raw access (your allocator, your generated bindings).
- `alloc` / `allocators`: allocators whose memory belongs to the enclosing scope; with `X.SIZE` they also type the result (NC6).
- `wrappers`: types that carry a pointer, such as a bounds-checked buffer (same rules as a pointer; their methods are not raw).
- `trust`: methods that take the caller's word for something later checks rely on (`pkg.Buf.wrap(pointer, size)`), allowed only where raw access is.
- `tracked`: exception types a method must declare when it lets them out (NC8 sees exception paths from signatures).
- `takes`: methods that take over a resource passed to them, so the caller no longer closes it (a no-op hand-over helper that says, in the code, "the C runtime owns this now" or "stays open until exit").
- `fails`, `nullable`: the NC10 annotation names.
- `callbacks`: methods that turn a named Java method into a C function pointer (default `org.teavm.interop.Function.get`); `foreign`, `same`: the NC9 annotation names; `unmanaged`: the annotation a `foreign` method must also carry (default `org.teavm.interop.Unmanaged`).
- `pointer`, `unsafe`, `handle`, `scoped`, `async`, `ctype`, `escapes`, `returned`, `acquires`, `owns`: type and annotation names, if yours differ.
- `suspend`: methods that suspend a fiber (defaults to sleep/join/yield/wait).
- `mode=warn`: report without failing, for a first look at an existing code base.

A complete worked setup is [jr](https://github.com/jarrunner/jr): the `<nativecheck>` property in `jr/pom.xml`, and the section "Native memory" of `jr/README.md`.

## Tests

`mvn test` compiles each file in `src/test/resources/cases/` with the plugin, against stub sources, and compares the rules reported with the file's first line (`// expect: NC1-field NC2-ofData`, or `// expect: none`). To add a case, add a file.

## A build note

The pom gives javac an empty `-processorpath`. Without it, javac 25 finds this plugin's own service file in `target/classes` from an earlier build and instantiates it (to ask `autoStart()`) before the plugin is compiled.
