#!/usr/bin/env python3

import boto3
from os import path
from datetime import datetime
import pprint
import csv
import re
import __main__

from math import ceil
from datagen import lib, util

import argparse

from datagen.util import split_passthrough_args

min_num_workers = 1
max_num_workers = 1000

defaults = {
    'bucket': 'ldbc-snb-datagen-store',
    'use_spot': False,
    'master_instance_type': 'm5d.2xlarge',
    'instance_type': 'r5d.2xlarge',
    'version': lib.version,
    'az': 'us-west-2c',
    'is_interactive': False,
    'ec2_key': None,
    'emr_release': 'emr-5.31.0'
}

pp = pprint.PrettyPrinter(indent=2)

dir = path.dirname(path.realpath(__file__))
ec2info_file = 'Amazon EC2 Instance Comparison.csv'


with open(path.join(dir, ec2info_file), mode='r') as infile:
    reader = csv.DictReader(infile)
    ec2_instances = [dict(row) for row in reader]


def calculate_cluster_config(scale_factor, sf_ratio=50.0):
    num_workers = max(min_num_workers, min(max_num_workers, ceil(scale_factor / sf_ratio)))
    return {
        'num_workers': num_workers,
        'parallelism_factor': max(1.0, sf_ratio / 50.0)
    }


def get_instance_info(instance_type):
    def parse_vcpu(col):
        return int(re.search(r'(\d+) .*', col).group(1))

    def parse_mem(col):
        return int(re.search(r'(\d+).*', col).group(1))

    vcpu = next((parse_vcpu(i['vCPUs']) for i in ec2_instances if i['API Name'] == instance_type), None)
    mem = next((parse_mem(i['Memory']) for i in ec2_instances if i['API Name'] == instance_type), None)

    if vcpu is None or mem is None:
        raise Exception(f'unable to find instance type `{instance_type}`. If not a typo, reexport `{ec2info_file}` from ec2instances.com')

    return {'vcpu': vcpu, 'mem': mem}


def submit_datagen_job(params_file, sf,
                       bucket=defaults['bucket'],
                       use_spot=defaults['use_spot'],
                       instance_type=defaults['instance_type'],
                       master_instance_type=defaults['master_instance_type'],
                       az=defaults['az'],
                       version=defaults['version'],
                       emr_release=defaults['emr_release'],
                       is_interactive=defaults['is_interactive'],
                       ec2_key=defaults['ec2_key'],
                       passthrough_args=None
                       ):

    exec_info = get_instance_info(instance_type)

    cluster_config = calculate_cluster_config(sf)

    emr = boto3.client('emr')

    name = path.splitext(params_file)[0]
    ts = datetime.utcnow()
    ts_formatted = ts.strftime('%Y%m%d_%H%M%S')

    jar_url = f's3://{bucket}/jars/ldbc_snb_datagen-{version}-jar-with-dependencies.jar'

    results_url = f's3://{bucket}/results/{name}'
    run_url = f'{results_url}/runs/{ts_formatted}'

    num_threads = ceil(cluster_config['num_workers'] * exec_info['vcpu'] * cluster_config['parallelism_factor'] * 2)

    spark_config = {
        'maximizeResourceAllocation': 'true',
        'spark.serializer': 'org.apache.spark.serializer.KryoSerializer'
    }

    hdfs_prefix = '/ldbc_snb_datagen'

    build_dir = f'{hdfs_prefix}/build'
    sn_dir = f'{hdfs_prefix}/social_network'

    market = 'SPOT' if use_spot else 'ON_DEMAND'

    ec2_key_dict = {'Ec2KeyName': ec2_key} if ec2_key is not None else {}

    job_flow_args = {
        'Name': f'{name}_{ts_formatted}',
        'LogUri': f's3://{bucket}/logs/emr',
        'ReleaseLabel': emr_release,
        'Applications': [
            {'Name': 'hadoop'},
            {'Name': 'spark'},
            {'Name': 'ganglia'}
        ],
        'Configurations': [
            {
                'Classification': 'spark',
                'Properties': spark_config
            }
        ],
        'Instances': {
            'InstanceGroups': [
                {
                    'Name': "Driver node",
                    'Market': 'ON_DEMAND',
                    'InstanceRole': 'MASTER',
                    'InstanceType': master_instance_type,
                    'InstanceCount': 1,
                },
                {
                    'Name': "Worker nodes",
                    'Market': market,
                    'InstanceRole': 'CORE',
                    'InstanceType': instance_type,
                    'InstanceCount': cluster_config['num_workers'],
                }
            ],
            **ec2_key_dict,
            'Placement': {'AvailabilityZone': az},
            'KeepJobFlowAliveWhenNoSteps': False,
            'TerminationProtected': False,
        },
        'JobFlowRole': 'EMR_EC2_DefaultRole',
        'ServiceRole': 'EMR_DefaultRole',
        'VisibleToAllUsers': True,
        'Steps': [
            {
                'Name': 'Run LDBC SNB Datagen',
                'ActionOnFailure': 'TERMINATE_CLUSTER',
                'HadoopJarStep': {
                    'Properties': [],
                    'Jar': 'command-runner.jar',
                    'Args': ['spark-submit', '--class', lib.main_class, jar_url,
                             '--output-dir', build_dir,
                             '--scale-factor', str(sf),
                             '--num-threads', str(num_threads),
                             *passthrough_args
                             ]
                }

            },
            {
                'Name': 'Save output',
                'ActionOnFailure': 'TERMINATE_CLUSTER',
                'HadoopJarStep': {
                    'Properties': [],
                    'Jar': 'command-runner.jar',
                    'Args': ['s3-dist-cp',
                             '--src', f'hdfs://{sn_dir}',
                             '--dest', f'{run_url}/social_network'
                             ]
                }
            }]
    }

    if is_interactive:
        job_flow_args_formatted = pp.pformat(job_flow_args)
        if not util.ask_continue(f'Job parameters:\n{job_flow_args_formatted}'):
            return

    emr.run_job_flow(**job_flow_args)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Submit a Datagen job to EMR')
    parser.add_argument('params_url',
                        type=str,
                        help='params file name')
    parser.add_argument('sf', type=int,
                        help='scale factor (used to calculate cluster size)')
    parser.add_argument('--use-spot',
                        action='store_true',
                        help='Use SPOT workers')
    parser.add_argument('--az',
                        default=defaults['az'],
                        help=f'Cluster availability zone. Default: {defaults["az"]}')
    parser.add_argument('--bucket',
                        default=defaults['bucket'],
                        help=f'LDBC SNB Datagen storage bucket. Default: {defaults["bucket"]}')
    parser.add_argument('--instance-type',
                        default=defaults['instance_type'],
                        help=f'Override the EC2 instance type used for worker nodes. Default: {defaults["instance_type"]}')
    parser.add_argument('--ec2-key',
                        default=defaults['ec2_key'],
                        help='EC2 key name for SSH connection')
    parser.add_argument('--version',
                        default=defaults['version'],
                        help='LDBC SNB Datagen library version')
    parser.add_argument('--emr-release',
                        default=defaults['emr_release'],
                        help='The EMR release to use. E.g emr-5.31.0, emr-6.1.0')
    parser.add_argument('-y',
                        action='store_true',
                        help='Assume \'yes\' for prompts')
    parser.add_argument('--', nargs='*', help='Arguments passed to LDBC SNB Datagen', dest="arg")

    self_args, child_args = split_passthrough_args()

    args = parser.parse_args(self_args)

    is_interactive = hasattr(__main__, '__file__')

    submit_datagen_job(args.params_url, args.sf,
                       bucket=args.bucket, use_spot=args.use_spot, az=args.az,
                       is_interactive=is_interactive and not args.y,
                       instance_type=args.instance_type,
                       emr_release=args.emr_release,
                       ec2_key=args.ec2_key,
                       version=args.version,
                       passthrough_args=child_args
                       )
