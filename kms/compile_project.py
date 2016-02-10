#!/usr/bin/python -t

import yaml
import argparse
import sys
import git
import os
from debian.deb822 import Deb822, PkgRelation
from debian.changelog import Changelog
from apt.cache import Cache
import apt_pkg
from time import time, strftime
from datetime import datetime

from git import Repo

import glob
import re

# sudo apt-get install python-git python-yaml python-apt python-debian

DEFAULT_CONFIG_FILE = '.build.yaml'

def clone_repo(args, base_url, repo_name):
  try:
    repo = Repo (repo_name)
    print ("Updating repo: " + repo_name)
    if args.no_update_git == None:
      for remote in repo.remotes:
        remote.update()
  except:
    print ("Cloning repo: " + repo_name)
    repo = Repo.clone_from (base_url + "/" + repo_name, repo_name)

  return repo

def check_dep(cache, pkg_name, req_version, commit):
  if cache.has_key (pkg_name):
    pkg = cache[pkg_name]
    if pkg.is_installed:
      #Check if version is valid
      if commit:
        return  pkg.installed.version.find (commit[:7]) >= 0
      elif req_version:
        comp = apt_pkg.version_compare (pkg.installed.version, req_version[1])
        if comp == 0 and req_version[0].find ("=") >= 0:
          return True
        if comp < 0 and req_version[0].find ("<") >= 0:
          return True
        if comp > 0 and req_version[0].find (">") >= 0:
          return True
      else:
        return True
  return False

def check_deb_dependency_installed (cache, dep):
  for dep_alternative in dep:
    name = dep_alternative["name"]
    dep_alternative.setdefault ("commit")

    if check_dep (cache, name, dep_alternative["version"], dep_alternative["commit"]):
      return True

  #If this code is reached, depdendency is not correctly installed in a valid version
  return False

def get_version_to_install (pkg, req_version, commit):
  for version in pkg.versions:
    if commit:
      if version.version.find (commit[:7]) >= 0:
        return version.version
    elif req_version:
      comp = apt_pkg.version_compare (version.version, req_version[1])
      if comp == 0 and req_version[0].find ("=") >= 0:
        return version.version
      if comp < 0 and req_version[0].find ("<") >= 0:
        return version.version
      if comp > 0 and req_version[0].find (">") >= 0:
        return version.version
    else:
      return version.version

  return None

def install_dependency (cache, dep):
  for dep_alternative in dep:
    pkg_name = dep_alternative["name"]
    if not cache.has_key (pkg_name):
      continue

    #Get package version to install that matches commit or version
    pkg = cache[pkg_name]
    dep_alternative.setdefault ("commit")
    version = get_version_to_install (pkg, dep_alternative["version"], dep_alternative["commit"])

    if version:
      version = "=" + version
    else:
      version = ""

    #Install selected dependency version
    os.system ("sudo postpone -d -f apt-get install --force-yes -y -q " + pkg_name + version)
    cache = Cache();
    gc.collect()
    if check_deb_dependency_installed (cache, dep):
      return True

  return False

def get_version ():
  version = os.popen("kurento_get_version.sh").read()
  return version

def get_debian_version (args):
  version = get_version ()

  last_release = os.popen ("git describe --abbrev=0 --tags || git rev-list --max-parents=0 HEAD").read()
  last_release = last_release[:last_release.rfind("\n"):]
  current_commit = os.popen ("git rev-parse --short HEAD").read()
  dist = os.popen ("lsb_release -c").read()
  dist = dist[dist.rfind(":")+1::].replace("\n", "").replace("\t", "").replace(" ", "")

  rc= os.popen ("git log " + last_release + "..HEAD --oneline | wc -l").read()
  rc = rc[:rc.rfind("\n"):]
  current_commit = current_commit[:current_commit.rfind("\n"):]
  version = version[:version.rfind("-"):]

  now = datetime.fromtimestamp(time())

  if int(rc) > 0:
    if args.simplify_dev_version > 0:
      version = version + "~" + rc + "." + current_commit + "." + dist
    else:
      version = version + "~" + now.strftime("%Y%m%d%H%M%S") + "." + rc + "." + current_commit + "." + dist
  else:
    version = version + "." + dist

  return version

def upload_package (args, package, publish=False):
  # TODO: Upload package to a repository
  print ("TODO: Upload package: " + package + " publish " + str(publish))
  pass

def generate_debian_package(args, config):
  debfile = Deb822 (open("debian/control"), fields=["Build-Depends", "Build-Depends-Indep"])

  rel_str = debfile.get("Build-Depends")
  if debfile.has_key ("Build-Depends-Indep"):
    rel_str = rel_str + "," + debfile.get("Build-Depends-Indep")

  relations = PkgRelation.parse_relations (rel_str)

  cache = Cache()

  #Check if all required packages are installed
  for dep in relations:
    if not check_deb_dependency_installed (cache, dep):
      #Install not found dependencies
      print ("Dependency not matched: " + str(dep))
      if not install_dependency (cache, dep):
        print ("Dependency cannot be installed: " + PkgRelation.str ([dep]))
        exit(1)

  changelog = Changelog (open("debian/changelog"))
  old_changelog = Changelog (open("debian/changelog"))
  new_version = get_debian_version (args)

  changelog.new_block(version=new_version, package=changelog.package, distributions="testing", changes=["\n  Generating new package version\n"], author=changelog.author, date=strftime("%a, %d %b %Y %H:%M:%S %z"), urgency=changelog.urgency)

  changelog.write_to_open_file (open("debian/changelog", 'w'))

  #Execute commands defined in config:
  if config.has_key ("prebuild-command"):
    print ("Executing prebuild-command: " + str(config["prebuild-command"]))
    os.system (config["prebuild-command"])

  if os.system("dpkg-buildpackage -nc -uc -us") != 0:
    print ("Error while generating package, try cleaning")
    os.system ("dpkg-buildpackage -uc -us")

  files = glob.glob("../*" + new_version + "_*.deb")
  for f in files:
    os.system("sudo dpkg -i " + f)
    if f is files[-1]:
      is_last=True
    else:
      is_last=False
    upload_package (args, f, publish=is_last)
  #In case packages were installed in a bad order and dependencies were not resolved
  os.system ("sudo apt-get install -f --force-yes -y -q")

  if args.clean > 0:
    files = glob.glob("../*" + new_version + "*")
    for f in files:
      os.remove(f)

  # Write old changelog to let everything as it was
  old_changelog.write_to_open_file (open("debian/changelog", 'w'))

def check_dependency_installed(cache, dependency):
  print ("Check dependency installed: " + str(dependency))

  ret_val = True

  f = open("debian/control");
  while True:
    debfile = Deb822 (f)

    if len (debfile) == 0:
      break

    if debfile.has_key ("Package"):
      pkg_name = debfile["Package"]
      dep = dependency
      dep ["name"] = pkg_name

      if not check_deb_dependency_installed (cache, [dep]):
        print ("Package not installed " + debfile["Package"])
        ret_val = ret_val and install_dependency(cache, [dep])

  return ret_val


def compile_project (args):
  workdir = os.getcwd()
  print ("Compile project: " + workdir);

  try:
    f = open (args.file, 'r')

    config = yaml.load (f)
  except:
    print ("Config file not found")
    return

  cache = Cache()
  # Parse dependencies and check if corrects versions are found
  if config.has_key ("dependencies"):
    #Parse dependencies config
    for dependency in config["dependencies"]:
      if not dependency.has_key("name"):
        print ("depenedency: >" + str(dependency) + "<\n needs a name")
        exit (1)
      if dependency.has_key("version"):
        regex = re.compile (r'(?P<relop>[>=<]+)\s*'
                r'(?P<version>[0-9a-zA-Z:\-+~.]+)')
        match = regex.match (dependency["version"])
        if match:
          parts = match.groupdict()
          dependency["version"] = (parts['relop'], parts['version'])
        else:
          print ("Invalid version for dependency " + dependency["name"])
          exit (1)
      else:
        dependency["version"] = None

    for dependency in config["dependencies"]:
      sub_project_name = dependency["name"]

      os.chdir("..")
      repo = clone_repo (args, args.base_url, sub_project_name)
      os.chdir(sub_project_name)

      if depenedency.has_key ("review"):
        # TODO: Set commit according to review
        pass

      if not check_dependency_installed (cache, dependency):
        print ("dependency not installed, compile it")
        # TODO: Change to the correct commit or git review
        compile_project (args)

      os.chdir(workdir)

  generate_debian_package(args, config)

def main():
  parser = argparse.ArgumentParser (description="Read configurations from .build.yaml")
  parser.add_argument ("--file", metavar="file", help="File to read config from", default=DEFAULT_CONFIG_FILE)
  parser.add_argument ("--base_url", metavar="base_url", help="Base repository url", required=True)
  parser.add_argument ("--simplify_dev_version", action="count", help="Simplify dev version, usefull for debugging")
  parser.add_argument ("--clean", action="count", help="Clean generated files when finished")
  parser.add_argument ("--no_update_git", action="count", help="Do not update git repositories of dependency projects")

  args = parser.parse_args()

  compile_project (args)

if __name__ == "__main__":
  main()
