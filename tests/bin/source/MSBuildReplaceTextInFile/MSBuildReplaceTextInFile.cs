using Microsoft.Build.Framework;
using System.Linq;

namespace MSBuildReplaceTextInFile
{
    public class MSBuildReplaceTextInFile : ITask
    {
        public IBuildEngine BuildEngine
        {
            get;
            set;
        }

        public ITaskHost HostObject
        {
            get;
            set;
        }

        [Required]
        public string File
        {
            get;
            set;
        }
        [Required]
        public string Find
        {
            get;
            set;
        }
        [Required]
        public string Replace
        {
            get;
            set;
        }
        public bool Execute()
        {
            string[] lines = System.IO.File.ReadAllLines(File);
            System.IO.File.Delete(File);
            System.IO.File.WriteAllLines(File, lines.Select(line => line.Replace(Find, Replace)).ToArray<string>());
            return true;
        }
    }
}
