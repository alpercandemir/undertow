"""Trust-boundary and isolated execution-plan regression tests; no network or Docker required."""
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile
from test_workflow_helpers import load_script

runner = load_script('run-approved-regression.py')
importer = load_script('import-ci.py')


def archive(path, files):
    with tarfile.open(path, 'w') as output:
        for name, data in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            output.addfile(info, io.BytesIO(data))


class IsolatedOracleTest(unittest.TestCase):
    def fixture(self, root):
        sources = {'pom.xml': b'<project/>', 'src/main/java/App.java': b'class App {}'}
        oracle = {'src/test/java/AppTest.java': b'class AppTest {}'}
        archive(root / 'source.tar', sources)
        archive(root / 'oracle.tar', oracle)
        digest = hashlib.sha256((root / 'oracle.tar').read_bytes()).hexdigest()
        plan = dict(schema_version=1, execution='isolated_ci_only', head_sha='a'*40,
                    image='example/maven@sha256:'+'b'*64, project_prefix='', artifact_id='regression', artifact_sha256=digest,
                    application_sources={key: hashlib.sha256(value).hexdigest() for key, value in sources.items()},
                    oracle_sources={key: hashlib.sha256(value).hexdigest() for key, value in oracle.items()},
                    contract_binding={'oracle_artifacts': {'regression': digest}})
        return plan

    def test_pinned_sources_and_oracle_inventory_are_enforced(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            plan = self.fixture(root)
            (root / 'archive-link.tar').symlink_to(root / 'source.tar')
            with self.assertRaises(ValueError):
                runner.archive_files(root / 'archive-link.tar')
            runner.prepare(plan, root / 'source.tar', root / 'oracle.tar', root / 'project')
            self.assertTrue((root / 'project/src/test/java/AppTest.java').is_file())
            archive(root / 'source.tar', {'pom.xml': b'<project/><!-- changed -->'})
            with self.assertRaises(ValueError):
                runner.prepare(plan, root / 'source.tar', root / 'oracle.tar', root / 'rejected')
            plan['image'] = 'maven:latest'
            with self.assertRaises(ValueError):
                runner.prepare(plan, root / 'source.tar', root / 'oracle.tar', root / 'rejected')

    def test_symlink_traversal_and_extra_oracle_files_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            plan = self.fixture(root)
            with tarfile.open(root / 'unsafe.tar', 'w') as output:
                link = tarfile.TarInfo('src/test/link')
                link.type = tarfile.SYMTYPE
                link.linkname = '/outside'
                output.addfile(link)
            with self.assertRaises(ValueError):
                runner.archive_files(root / 'unsafe.tar')
            archive(root / 'unsafe.tar', {'../outside': b'x'})
            with self.assertRaises(ValueError):
                runner.archive_files(root / 'unsafe.tar')
            archive(root / 'oracle.tar', {'pom.xml': b'unapproved build configuration'})
            plan['artifact_sha256'] = hashlib.sha256((root / 'oracle.tar').read_bytes()).hexdigest()
            plan['contract_binding']['oracle_artifacts']['regression'] = plan['artifact_sha256']
            with self.assertRaises(ValueError):
                runner.prepare(plan, root / 'source.tar', root / 'oracle.tar', root / 'rejected')

    def test_executor_has_resource_network_filesystem_and_credential_boundaries(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            plan = self.fixture(root)
            command = runner.docker_arguments(plan, root / 'project', root / 'cache', root / 'out', 'synthetic-run')
            for flag in ('--network=none', '--read-only', '--cpus=1', '--memory=1g', '--pids-limit=128',
                         '--cap-drop=ALL', '--security-opt=no-new-privileges', '--user=65534:65534', '--pull=never'):
                self.assertIn(flag, command)
            self.assertNotIn('/var/run/docker.sock', ' '.join(command))
            self.assertNotIn('GEMINI_API_KEY', ' '.join(command))

    def test_importer_keeps_build_and_external_oracle_subjects(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as archive_file:
            archive_file.writestr('build-provenance.json', json.dumps(dict(schema_version=1, head_sha='a'*40, artifact_sha256='b'*64)))
            archive_file.writestr('oracle-execution.json', json.dumps(dict(schema_version=1, head_sha='a'*40,
                                      oracle_artifacts={'regression': 'c'*64}, image='example/maven@sha256:'+'d'*64)))
        records = dict(importer.normalize_archive(data.getvalue()))
        self.assertEqual(records['build']['head_sha'], 'a'*40)
        self.assertEqual(records['oracle']['oracle_artifacts']['regression'], 'c'*64)
