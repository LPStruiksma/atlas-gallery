#!/usr/bin/env python3
"""Independent synthetic forward-coordinate oracle; no photos, EXIF IO or models."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
HARNESS = r'''
import java.security.MessageDigest;
public final class FictionalExifHarness {
  static int[] pixels(int w,int h) {
    int[] a=new int[w*h];
    for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
      a[y*w+x]=0xff000000 | ((x*31+y*11)&255)<<16
        | ((x*7+y*53)&255)<<8 | ((x*91+y*17)&255);
    }
    return a;
  }
  static String sha(byte[] a) throws Exception {
    StringBuilder b=new StringBuilder();
    for(byte v:MessageDigest.getInstance("SHA-256").digest(a))
      b.append(String.format("%02x",v&255));
    return b.toString();
  }
  public static void main(String[] args) throws Exception {
    int[][] cases={{2,3},{3,2},{7,5},{13,11},{8,6}};
    for(int[] d:cases) {
      int w=d[0],h=d[1]; int[] input=pixels(w,h);
      for(int o=1;o<=8;o++) {
        int ow=o>=5?h:w,oh=o>=5?w:h;
        int[][] boxes={{0,0,ow,oh},{0,0,1,1},{ow-1,oh-1,ow,oh},
          {ow/4,oh/4,Math.max(ow/4+1,3*ow/4),Math.max(oh/4+1,3*oh/4)}};
        for(int[] box:boxes) {
          ExifOrientationCropCanary.Result r=ExifOrientationCropCanary.orientAndCrop(
            input,w,h,o,new ExifOrientationCropCanary.Rect(
              box[0],box[1],box[2],box[3]));
          System.out.println(w+" "+h+" "+o+" "+box[0]+" "+box[1]+" "+box[2]+" "
            +box[3]+" "+r.orientedWidth+" "+r.orientedHeight+" "+sha(r.cropRgb));
        }
      }
    }
  }
}
'''
NEGATIVE = r'''
public final class FictionalExifNegatives {
  interface Attempt { void run(); }
  static void refuses(Attempt a) {
    try { a.run(); } catch (IllegalArgumentException expected) {return;}
    throw new AssertionError("Invalid synthetic metadata accepted");
  }
  public static void main(String[] args) {
    final int[] v={0xff010203,0xff040506,0xff070809,
                   0xff0a0b0c,0xff0d0e0f,0xff101112};
    final ExifOrientationCropCanary.Rect box=
      new ExifOrientationCropCanary.Rect(0,0,3,2);
    refuses(() -> ExifOrientationCropCanary.orientAndCrop(v,3,2,0,box));
    refuses(() -> ExifOrientationCropCanary.orientAndCrop(v,3,2,9,box));
    refuses(() -> ExifOrientationCropCanary.orientAndCrop(v,3,2,6,box));
    refuses(() -> ExifOrientationCropCanary.orientAndCrop(v,3,2,1,
      new ExifOrientationCropCanary.Rect(0,0,2,3)));
    refuses(() -> ExifOrientationCropCanary.orientAndCrop(v,3,2,1,null));
    refuses(() -> ExifOrientationCropCanary.orientAndCrop(null,3,2,1,box));
    refuses(() -> ExifOrientationCropCanary.orientAndCrop(new int[0],3,2,1,box));
    refuses(() -> ExifOrientationCropCanary.orientAndCrop(v,513,2,1,box));
    refuses(() -> ExifOrientationCropCanary.orientAndCrop(v,Integer.MAX_VALUE,
      Integer.MAX_VALUE,1,box));
    refuses(() -> ExifOrientationCropCanary.orientAndCrop(v,3,2,1,
      new ExifOrientationCropCanary.Rect(0,0,0,1)));
    refuses(() -> ExifOrientationCropCanary.orientAndCrop(v,3,2,1,
      new ExifOrientationCropCanary.Rect(-1,0,1,1)));
    refuses(() -> ExifOrientationCropCanary.orientAndCrop(v,3,2,1,
      new ExifOrientationCropCanary.Rect(0,0,4,2)));
    int[] alpha=v.clone(); alpha[3]=0x80010203;
    refuses(() -> ExifOrientationCropCanary.orientAndCrop(alpha,3,2,1,box));
    ExifOrientationCropCanary.Result oriented=
      ExifOrientationCropCanary.orientAndCrop(v,3,2,6,
        new ExifOrientationCropCanary.Rect(0,0,2,3));
    if(oriented.orientedWidth!=2||oriented.orientedHeight!=3
       ||oriented.cropRgb.length!=18||v[0]!=0xff010203)
       throw new AssertionError("Invalid oriented shape or source changed");
    System.out.println("{\"negative_exif_bounds_alpha_tests\":true,"
     +"\"photos_used\":false,\"auto_confirm_allowed\":false}");
  }
}
'''

def forward(x, y, w, h, mode):
    """Map raw-to-oriented pixel, independent from Java inverse mapping."""
    if mode == 1: return x,y
    if mode == 2: return w-1-x,y
    if mode == 3: return w-1-x,h-1-y
    if mode == 4: return x,h-1-y
    if mode == 5: return y,x
    if mode == 6: return h-1-y,x
    if mode == 7: return h-1-y,w-1-x
    if mode == 8: return y,w-1-x
    raise AssertionError("Invalid orientation in test")

def oracle(w,h,mode,rect):
    ow,oh=(h,w) if mode>=5 else (w,h)
    full=bytearray(ow*oh*3)
    touched=set()
    for sy in range(h):
        for sx in range(w):
            x,y=forward(sx,sy,w,h,mode)
            at=3*(y*ow+x)
            if at in touched: raise AssertionError("Non-bijective oracle")
            touched.add(at)
            full[at:at+3]=bytes(((sx*31+sy*11)&255,
                                (sx*7+sy*53)&255,
                                (sx*91+sy*17)&255))
    if len(touched)!=w*h: raise AssertionError("Incomplete oracle")
    l,t,r,b=rect
    crop=bytearray()
    for y in range(t,b):
        for x in range(l,r):
            crop.extend(full[3*(y*ow+x):3*(y*ow+x)+3])
    return hashlib.sha256(crop).hexdigest()

@unittest.skipUnless(shutil.which("javac") and shutil.which("java"),
                     "Java 8-compatible JDK unavailable")
class SyntheticExifGeometryTests(unittest.TestCase):
    def compile(self,root):
        sources=[HERE/"ExifOrientationCropCanary.java"]
        for name,body in (("FictionalExifHarness.java",HARNESS),
                          ("FictionalExifNegatives.java",NEGATIVE)):
            file=root/name
            file.write_text(body,encoding="utf-8")
            sources.append(file)
        p=subprocess.run(["javac","-Xlint:-options","--release","8","-d",
                           str(root),*(str(x) for x in sources)],
                          capture_output=True,text=True,timeout=45)
        self.assertEqual(p.returncode,0,p.stderr[:900])

    def test_160_crop_vectors_against_independent_forward_oracle(self):
        with tempfile.TemporaryDirectory(prefix="synthetic-exif-coordinates-") as temp:
            root=Path(temp)
            self.compile(root)
            p=subprocess.run(["java","-cp",temp,"FictionalExifHarness"],
                              capture_output=True,text=True,timeout=30)
            self.assertEqual(p.returncode,0,p.stderr[:900])
            records=p.stdout.splitlines()
            self.assertEqual(len(records),160)
            for record in records:
                parts=record.split()
                self.assertEqual(len(parts),10)
                w,h,mode,l,t,r,b,ow,oh=map(int,parts[:9])
                self.assertEqual((ow,oh),(h,w) if mode>=5 else (w,h))
                self.assertEqual(parts[9],oracle(w,h,mode,(l,t,r,b)))
            distinct=[oracle(7,5,mode,(0,0,5 if mode>=5 else 7,
                                         7 if mode>=5 else 5))
                      for mode in range(1,9)]
            self.assertEqual(len(set(distinct)),8)

    def test_negative_geometry_alpha_and_no_source_mutation(self):
        with tempfile.TemporaryDirectory(prefix="synthetic-exif-negatives-") as temp:
            root=Path(temp)
            self.compile(root)
            p=subprocess.run(["java","-cp",temp,"FictionalExifNegatives"],
                              capture_output=True,text=True,timeout=30)
            self.assertEqual(p.returncode,0,p.stderr[:900])
            report=json.loads(p.stdout)
            self.assertTrue(report["negative_exif_bounds_alpha_tests"])
            self.assertFalse(report["photos_used"])
            self.assertFalse(report["auto_confirm_allowed"])

    def test_selftest_accepts_no_file_path(self):
        with tempfile.TemporaryDirectory(prefix="synthetic-exif-selftest-") as temp:
            root=Path(temp)
            self.compile(root)
            p=subprocess.run(["java","-cp",temp,"ExifOrientationCropCanary",
                              "--self-test"],capture_output=True,text=True,timeout=30)
            self.assertEqual(p.returncode,0,p.stderr[:900])
            self.assertTrue(json.loads(p.stdout)["eight_exif_modes_structurally_verified"])
            p=subprocess.run(["java","-cp",temp,"ExifOrientationCropCanary",
                              "/private/portrait.jpg"],capture_output=True,text=True,timeout=30)
            self.assertEqual(p.returncode,2)
            self.assertNotIn("portrait",p.stderr)

if __name__ == "__main__":
    unittest.main(verbosity=2)
