using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace Microsoft.VisualStudio.Tools.Build
{
    internal class Reference
    {
        public Reference(string name, Version version)
        {
            Name = name;
            Version = version;
        }

        public string Name
        {
            get;
            private set;
        }

        public Version Version
        {
            get;
            private set;
        }

        public override bool Equals(object obj)
        {
            Reference r = obj as Reference;
            if (r == null)
                return false;
            return Name == r.Name && Version == r.Version;
        }

        public static Reference Parse(string referenceString)
        {
            if (String.IsNullOrEmpty(referenceString))
            {
                throw new ArgumentException(nameof(referenceString));
            }

            // name,version
            var parts = referenceString.Split(',');

            return new Reference(parts[0], parts.Length > 1 ? new Version(parts[1]) : null);
        }

        public override int GetHashCode()
        {
            return Name.GetHashCode() ^ Version.GetHashCode();
        }
    }
}
