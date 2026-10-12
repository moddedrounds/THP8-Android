import struct
import sys

data = open(sys.argv[1], "rb").read()
words = struct.unpack("<%dI" % (len(data) // 4), data)
rows = [", ".join("0x%08X" % w for w in words[i:i + 8]) for i in range(0, len(words), 8)]
print(",\n".join("    " + r for r in rows))
