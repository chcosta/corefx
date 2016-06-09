using Microsoft.Cci;
using Microsoft.VisualStudio.Tools.Build;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;

namespace verifyClosure
{
    class Program
    {
        static Dictionary<string, AssemblyInfo> knownAssemblies = new Dictionary<string, AssemblyInfo>();
        static List<string> knownNativeAssemblies = new List<string>();
        static HashSet<string> baselineErrors;
        static HashSet<string> errors = new HashSet<string>();
        static string baselineFilename;
        static Dictionary<string, Version> ignoreReferences = new Dictionary<string, Version>();

        private static bool checkGAC = false;
        private static bool pInvokeCheck = true;
        private static string path;

        static void Main(string[] args)
        {
            foreach (string arg in args)
            {
                if(arg.Equals("/checkgac", StringComparison.InvariantCultureIgnoreCase))
                {
                    checkGAC = true;
                    continue;
                }
                if(arg.Equals("/nocheckpinvokes", StringComparison.InvariantCultureIgnoreCase))
                {
                    pInvokeCheck = false;
                    continue;
                }
                if(arg.StartsWith("/baselinefile="))
                {
                    baselineFilename = arg.Substring(arg.IndexOf('=') + 1);
                }
                if (arg.StartsWith("/ignore="))
                {
                    string ignoreRefs = arg.Substring(arg.IndexOf("=") + 1);
                    foreach(var ignoreRef in ignoreRefs.Split(';').Select(rs => Reference.Parse(rs)))
                    {
                        ignoreReferences.Add(ignoreRef.Name, ignoreRef.Version);
                    }
                }
                if (File.Exists(arg))
                {
                    try
                    {
                        var assm = AssemblyInfo.GetAssemblyInfo(arg);
                        knownAssemblies.Add(assm.Name, assm);
                    }
                    catch (InvalidOperationException)
                    { }
                }
                if(Directory.Exists(arg))
                {
                    if (!string.IsNullOrEmpty(path))
                    {
                        throw new Exception("Invalid arguments, only one 'directory' is allowed on the command-line.");
                    }
                    path = arg;
                    foreach (var file in Directory.EnumerateFiles(arg, "*", SearchOption.AllDirectories))
                    {
                        if (Path.GetExtension(file).Equals(".pdb"))
                        {
                            continue;
                        }
                        try
                        {
                            var assm = AssemblyInfo.GetAssemblyInfo(file);

                            if (ShouldIgnore(assm.Name, assm.Version))
                            {
                                continue;
                            }

                            // either a new assembly or higher version than we already have.
                            if (!knownAssemblies.ContainsKey(assm.Name) || (assm.Version > knownAssemblies[assm.Name].Version))
                            {
                                knownAssemblies[assm.Name] = assm;
                            }
                        }
                        catch (InvalidOperationException)
                        {
                            // The native assemblies won't have PE headers.
                            knownNativeAssemblies.Add(Path.GetFileNameWithoutExtension(file).ToLower());
                        }
                        catch (BadImageFormatException)
                        {
                            // The native assemblies won't have PE headers.
                            knownNativeAssemblies.Add(Path.GetFileNameWithoutExtension(file).ToLower());
                        }
                    }
                }
            }
            if (File.Exists(baselineFilename))
            {
                baselineErrors = new HashSet<string>(File.ReadAllLines(baselineFilename));
            }

            MetadataReaderHost host = new PeReader.DefaultHost();

            foreach (var assm in knownAssemblies.Values)
            {
                foreach(var dep in assm.References.Where(r => !ShouldIgnore(r)))
                {
                    if(!IsKnownAssembly(dep.Name, dep.Version))
                    {
                        AddError(String.Format("{0} is missing {1}, {2}", assm.Name, dep.Name, dep.Version));
                    }
                }

                var rootedAssm = !string.IsNullOrEmpty(path) ? Path.Combine(path, assm.Name + ".dll") : assm.Name + ".dll";
                var assembly = host.LoadUnitFrom(rootedAssm) as IAssembly;
                if (assembly == null || assembly == Dummy.Assembly)
                {
                    // Not a PE file containing a CLR assembly, or an error occurred when loading it.
                    continue;
                }
                try
                {
                    if (pInvokeCheck)
                    {
                        // Look for native pInvokes so that we can ensure native dependencies are in our known assemblies list as well.
                        foreach (INamedTypeDefinition type in assembly.GetAllTypes())
                        {
                            foreach (IMethodDefinition methodDefinition in type.Methods)
                            {
                                if (methodDefinition.IsPlatformInvoke)
                                {
                                    var pInvokeData = methodDefinition.PlatformInvokeData;
                                    string assemblyImport = pInvokeData.ImportModule.Name.Value;
                                    // Windows assembly imports include the ".dll" extension, Unix does not include the extension
                                    if (Path.GetExtension(assemblyImport).Length == 4)
                                    {
                                        assemblyImport = Path.GetFileNameWithoutExtension(assemblyImport);
                                    }
                                    if (!IsKnownAssembly(assemblyImport, Version.Parse("0.0.0.0")))
                                    {
                                        AddError(String.Format("{0} is missing native assembly {1}", assm.Name, assemblyImport));
                                    }
                                }
                            }
                        }
                    }
                }
                catch (Exception e)
                {
                    Console.WriteLine("Exception {0}: {1}", e.GetType().Name, e);
                }
            }
            if (errors.Count > 0)
            {
                foreach (string error in errors)
                    Console.WriteLine(string.Concat("\t", error));
            }
        }
        
        private static bool ShouldIgnore(Reference reference)
        {
            return ShouldIgnore(reference.Name, reference.Version);
        }

        private static bool ShouldIgnore(string name, Version version)
        {
            Version ignoreVersion = null;
            return ignoreReferences.TryGetValue(name, out ignoreVersion) &&
                (ignoreVersion == null || ignoreVersion >= version);
        }

        private static bool IsKnownAssembly(string assemblyName, Version assemblyVersion)
        {
            AssemblyInfo assemblyTarget = null;
            bool isKnown = true;
            if (knownNativeAssemblies.Contains(assemblyName.ToLower()))
            {
                // native assemblies won't be in the Gac
                return isKnown;
            }

            if (!knownAssemblies.TryGetValue(assemblyName, out assemblyTarget))
            {
                bool isInGac = false;
                if (checkGAC)
                {
                    isInGac = IsInGac(assemblyName, assemblyVersion);
                }
                if (!isInGac)
                {
                    isKnown = false;
                }
            }
            else if (assemblyTarget.Version < assemblyVersion)
            {
                AddError(String.Format("Insufficient version: {0}, {1} < {2}", assemblyName, assemblyTarget.Version, assemblyVersion));
                isKnown = false;
            }

            return isKnown;
        }
        private static bool IsInGac(string assemblyName, Version minimumVersion)
        {
            try
            {
                Assembly gacAssm = Assembly.LoadWithPartialName(assemblyName);
                string imageRuntimeVersion = gacAssm.ImageRuntimeVersion.TrimStart('v');
                Version ver = Version.Parse(imageRuntimeVersion);

                if (ver < minimumVersion)
                {
                    AddError(String.Format("Insufficient version: {0}, {1} < {2}", assemblyName, ver, minimumVersion));
                }
                else
                {
                    return true;
                }
            }
            catch (Exception) { }
            return false;
        }
        private static void AddError(string errorMessage)
        {
            if(baselineErrors?.FirstOrDefault(error => error.Trim().Equals(errorMessage, StringComparison.InvariantCultureIgnoreCase)) != null)
            {
                return;
            }

            errors.Add(errorMessage.Trim());
        }
    }
}
